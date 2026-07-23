#!/usr/bin/env python3
"""Génération du dataset synthétique de tickets de support (S2-J5).

**Aucune dépendance externe** : bibliothèque standard uniquement (`urllib`). Les fournisseurs
LLM (Groq, Gemini, OpenRouter) exposent tous une API *compatible OpenAI*, donc un simple POST
HTTP suffit — pas besoin de litellm (qui traîne des extensions Rust pénibles à compiler sous
Windows). La chaîne de repli reproduit celle du service (`ai-service/app/core/llm.py`).

Stratégie (voir eval/README.md pour la méthodologie complète) :

1. **Génération conditionnée** — on tire d'abord une cible (catégorie, priorité, sentiment,
   langue, style, longueur) et on demande au LLM d'écrire un ticket qui *colle* à cette cible.
   Le label est la *consigne*, pas une prédiction : fiable par construction.

2. **Filtre d'accord (test set uniquement)** — un second appel LLM *reclasse à l'aveugle*
   (il ne voit que le texte). On ne garde le ticket que si la reclassification concorde avec la
   consigne sur les champs sous contrôle (catégorie + sentiment par défaut). Substitut honnête
   à l'étiquetage humain.

3. **Équilibrage & étanchéité** — catégories équilibrées sur FR/EN ; dédup par texte normalisé ;
   le test est construit EN PREMIER, donc le train ne peut pas reproduire un ticket du test.

Usage :
    python eval/generate_dataset.py --train-n 800 --test-n 300

Les clés API sont lues depuis .env (GROQ_API_KEY / GEMINI_API_KEY / OPENROUTER_API_KEY).
"""
from __future__ import annotations

import argparse
import itertools
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path

# --- Espace de labels (miroir de ai-service/app/schemas.py) -----------------

CATEGORIES = ["TECHNIQUE", "FACTURATION", "COMPTE", "RECLAMATION", "DEMANDE"]
PRIORITIES = ["LOW", "MEDIUM", "HIGH"]
SENTIMENTS = ["NEG", "NEU", "POS"]
LANGUAGES = ["fr", "en"]
STYLES = ["formal", "angry", "telegraphic", "neutral", "polite"]
LENGTHS = ["short", "medium", "long"]

CATEGORY_DEFINITIONS = (
    "- TECHNIQUE: the website/app/service is broken — bug, error, page not loading, feature not working.\n"
    "- FACTURATION: money & billing — charges, invoices, refunds, failed payments, pricing.\n"
    "- COMPTE: account access & profile — login, password reset, email change, locked account.\n"
    "- RECLAMATION: complaint about an order/product/delivery/service — late, damaged, wrong item, poor service.\n"
    "- DEMANDE: general request or question — how-to, availability, information, feature request."
)

REPO_ROOT = Path(__file__).resolve().parents[1]


# --- .env + passerelle LLM (stdlib) -----------------------------------------

def load_env(path: Path) -> None:
    """Charge un .env minimal dans os.environ (KEY=VALUE ; # commentaires ignorés)."""
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip().strip('"').strip("'"))


def collect_keys(key_env: str) -> list[str]:
    """Toutes les clés d'un fournisseur : GROQ_API_KEY, puis GROQ_API_KEY_2, _3, … et/ou une
    liste séparée par des virgules dans une seule variable. Permet d'empiler plusieurs comptes
    pour contourner les quotas quotidiens (bascule automatique au 429)."""
    keys: list[str] = []
    seen: set[str] = set()

    def add(raw: str) -> None:
        for k in raw.split(","):
            k = k.strip()
            if k and k not in seen:
                seen.add(k)
                keys.append(k)

    if os.environ.get(key_env):
        add(os.environ[key_env])
    i = 2
    while os.environ.get(f"{key_env}_{i}"):
        add(os.environ[f"{key_env}_{i}"])
        i += 1
    return keys


def build_providers(model_override: str | None) -> list[dict]:
    """Fournisseurs disponibles (une entrée par clé), dans l'ordre de repli."""
    catalog = [
        # 8b-instant : bien plus de tokens/jour en gratuit que le 70b (100k TPD), suffisant pour
        # générer des tickets courts. Forcer le 70b si besoin via --model llama-3.3-70b-versatile.
        ("GROQ_API_KEY", "https://api.groq.com/openai/v1/chat/completions", "llama-3.1-8b-instant"),
        ("GEMINI_API_KEY", "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions", "gemini-2.0-flash"),
        ("OPENROUTER_API_KEY", "https://openrouter.ai/api/v1/chat/completions", "meta-llama/llama-3.1-8b-instruct:free"),
    ]
    providers = []
    for key_env, url, default_model in catalog:
        base = key_env.replace("_API_KEY", "")
        for i, api_key in enumerate(collect_keys(key_env), start=1):
            providers.append({
                "name": base if i == 1 else f"{base}#{i}",
                "url": url,
                "model": model_override or default_model,
                "api_key": api_key,
            })
    if not providers:
        sys.exit("Aucune clé API trouvée dans .env (GROQ_API_KEY / GEMINI_API_KEY / OPENROUTER_API_KEY).")
    return providers


# UA neutre : le défaut "Python-urllib/x" est bloqué par le WAF Cloudflare de Groq (403 code 1010).
USER_AGENT = "SupportIQ-eval/1.0 (+https://github.com/supportiq)"
THROTTLE_S = 2.0  # pause après chaque appel (respect du rate limit gratuit Groq ~30 req/min)


def _post(url: str, api_key: str, payload: dict, timeout: int = 90) -> dict:
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=data, method="POST",
        headers={"Authorization": f"Bearer {api_key}", "Content-Type": "application/json",
                 "User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def complete(messages: list[dict], providers: list[dict], temperature: float) -> str:
    """Appel chat-completions compatible OpenAI, avec repli sur les fournisseurs et retries."""
    errors: list[str] = []
    for prov in providers:
        api_key = prov["api_key"]
        name = prov["name"]
        err = f"{name}: échec inconnu"
        for attempt in range(3):
            # 1er essai avec response_format json ; certains fournisseurs le refusent -> 2e sans.
            for rf in ({"type": "json_object"}, None):
                payload = {"model": prov["model"], "messages": messages,
                           "temperature": temperature, "max_tokens": 2048}
                if rf:
                    payload["response_format"] = rf
                try:
                    resp = _post(prov["url"], api_key, payload)
                    time.sleep(THROTTLE_S)  # respect du rate limit avant de rendre la main
                    return resp["choices"][0]["message"]["content"]
                except urllib.error.HTTPError as e:
                    body = e.read().decode("utf-8", "ignore")[:250]
                    err = f"{name}({prov['model']}) HTTP {e.code}: {body}"
                except Exception as e:  # timeout, réseau, JSON…
                    err = f"{name}({prov['model']}): {e}"
            time.sleep(0.8 * (attempt + 1))
        errors.append(err)  # dernière erreur de CE fournisseur
    raise RuntimeError("Tous les fournisseurs LLM ont échoué :\n  - " + "\n  - ".join(errors))


def extract_json(raw: str) -> dict:
    """Parse le PREMIER objet JSON du texte, en ignorant tout ce qui suit (certains modèles
    ajoutent du blabla ou un 2e objet après → 'Extra data'). `raw_decode` s'arrête à la fin
    du premier objet valide."""
    raw = raw.strip()
    fence = re.search(r"```(?:json)?\s*(.*?)```", raw, re.DOTALL)
    if fence:
        raw = fence.group(1).strip()
    start = raw.find("{")
    if start == -1:
        raise json.JSONDecodeError("aucun objet JSON", raw, 0)
    obj, _ = json.JSONDecoder().raw_decode(raw[start:])
    return obj


# --- Validation légère (sans pydantic) --------------------------------------

def parse_ticket(item: object) -> dict | None:
    if not isinstance(item, dict):
        return None
    subject, body = item.get("subject"), item.get("body")
    if not isinstance(subject, str) or not subject.strip():
        return None
    if not isinstance(body, str) or not body.strip():
        return None
    return {"subject": subject.strip(), "body": body.strip()}


def parse_pred(item: object) -> dict | None:
    if not isinstance(item, dict):
        return None
    try:
        return {
            "category": str(item["category"]).strip().upper(),
            "priority": str(item["priority"]).strip().upper(),
            "sentiment": str(item["sentiment"]).strip().upper(),
        }
    except (KeyError, TypeError):
        return None


# --- Prompts ----------------------------------------------------------------

GEN_SYSTEM = (
    "You generate REALISTIC customer-support tickets for an online retailer. "
    "Return STRICT JSON only — no prose, no markdown fences.\n\n"
    "Category meanings (the ticket must clearly belong to the given category):\n"
    + CATEGORY_DEFINITIONS
    + "\n\nFor EACH ticket, obey its spec exactly:\n"
    "- Write ONLY in the requested language (fr or en).\n"
    "- Match the requested priority, sentiment, style and length.\n"
    "- Styles: formal=polite corporate; angry=frustrated (capitals/!, ok); telegraphic=short fragments, "
    "no niceties; neutral=plain factual; polite=friendly warm.\n"
    "- Lengths: short=1 sentence; medium=2-3 sentences; long=4-6 sentences.\n"
    "- NEVER mention the category/priority/sentiment words in the text. Vary names, products, order numbers, details.\n"
    "- subject = short realistic subject line; body = the message."
)

CLS_SYSTEM = (
    "You are a strict support-ticket classifier. Read each ticket and assign labels. "
    "Return STRICT JSON only.\n\n"
    "Categories:\n" + CATEGORY_DEFINITIONS + "\n"
    "Priority: LOW, MEDIUM, HIGH. Sentiment: NEG (negative), NEU (neutral), POS (positive)."
)


def gen_batch(specs: list[dict], providers: list[dict]) -> list[dict]:
    user = (
        "Generate one ticket per spec, in the SAME order. "
        'Return JSON: {"tickets":[{"subject":"...","body":"..."}, ...]}.\nSpecs:\n'
        + json.dumps(specs, ensure_ascii=False)
    )
    try:
        data = extract_json(complete(
            [{"role": "system", "content": GEN_SYSTEM}, {"role": "user", "content": user}],
            providers, temperature=0.9))
    except json.JSONDecodeError:
        return []  # réponse illisible : lot ignoré, la boucle réessaiera
    tickets = [parse_ticket(x) for x in data.get("tickets", [])]
    return [t for t in tickets if t is not None]


def classify_batch(texts: list[str], providers: list[dict]) -> list[dict | None]:
    user = (
        "Classify EACH ticket, in the SAME order. "
        'Return JSON: {"predictions":[{"category":"..","priority":"..","sentiment":".."}, ...]}.\n'
        "Tickets:\n" + json.dumps([{"text": t} for t in texts], ensure_ascii=False)
    )
    try:
        data = extract_json(complete(
            [{"role": "system", "content": CLS_SYSTEM}, {"role": "user", "content": user}],
            providers, temperature=0.0))
    except json.JSONDecodeError:
        return [None] * len(texts)  # classif illisible : candidats rejetés ce tour-ci
    preds = [parse_pred(x) for x in data.get("predictions", [])]
    while len(preds) < len(texts):
        preds.append(None)
    return preds[: len(texts)]


# --- Échantillonnage & enregistrement ---------------------------------------

def make_specs(category: str, language: str, n: int, rng: random.Random) -> list[dict]:
    combos = list(itertools.product(PRIORITIES, SENTIMENTS))  # 9 combinaisons priorité×sentiment
    rng.shuffle(combos)
    specs = []
    for i in range(n):
        priority, sentiment = combos[i % len(combos)]
        specs.append({
            "language": language, "category": category,
            "priority": priority, "sentiment": sentiment,
            "style": rng.choice(STYLES), "length": rng.choice(LENGTHS),
        })
    return specs


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", text.lower()).strip()


def agrees(spec: dict, pred: dict, gate: list[str]) -> bool:
    return all(pred.get(f) == spec[f] for f in gate)


def record(spec: dict, ticket: dict, split: str, idx: int) -> dict:
    return {
        "id": f"syn-{split}-{idx:05d}",
        "language": spec["language"],
        "subject": ticket["subject"],
        "body": ticket["body"],
        "text": f"{ticket['subject']}\n\n{ticket['body']}",
        "category": spec["category"],
        "priority": spec["priority"],
        "sentiment": spec["sentiment"],
        "style": spec["style"],
        "split": split,
        "source": "synthetic",
    }


# --- Construction des splits -------------------------------------------------

def build_test(rows: list[dict], stats: dict, n: int, batch: int, gate: list[str],
               providers: list[dict], judges: list[dict], rng: random.Random, seen: set[str]) -> None:
    """Remplit `rows`/`stats` en place. Génération via `providers`, filtre d'accord via `judges`
    (souvent un modèle plus fort : c'est lui qui valide la qualité des labels du test set)."""
    per_cell = max(1, n // (len(CATEGORIES) * len(LANGUAGES)))
    for category in CATEGORIES:
        for language in LANGUAGES:
            kept, attempts = 0, 0
            while kept < per_cell and attempts < per_cell * 4:
                attempts += 1
                specs = make_specs(category, language, min(batch, per_cell - kept + 2), rng)
                pairs = [(s, t) for s, t in zip(specs, gen_batch(specs, providers))
                         if normalize(t["body"]) not in seen]
                if not pairs:
                    continue
                preds = classify_batch([f"{t['subject']}\n\n{t['body']}" for _, t in pairs], judges)
                stats["generated"] += len(pairs)
                for (spec, ticket), pred in zip(pairs, preds):
                    if kept >= per_cell:
                        break
                    if pred is None or not agrees(spec, pred, gate):
                        stats["rejected"] += 1
                        continue
                    if pred.get("priority") == spec["priority"]:
                        stats["priority_agree"] += 1
                    seen.add(normalize(ticket["body"]))
                    rows.append(record(spec, ticket, "test", len(rows)))
                    kept += 1
                    stats["kept"] += 1
            print(f"  test [{category}/{language}] {kept}/{per_cell}", file=sys.stderr)


def build_train(rows: list[dict], n: int, batch: int, providers: list[dict],
                rng: random.Random, seen: set[str]) -> None:
    """Remplit `rows` en place. Génération conditionnée sans filtre (label = consigne)."""
    per_cell = max(1, n // (len(CATEGORIES) * len(LANGUAGES)))
    for category in CATEGORIES:
        for language in LANGUAGES:
            produced = 0
            specs = make_specs(category, language, per_cell, rng)
            for start in range(0, len(specs), batch):
                chunk = specs[start : start + batch]
                for spec, ticket in zip(chunk, gen_batch(chunk, providers)):
                    if normalize(ticket["body"]) in seen:
                        continue
                    seen.add(normalize(ticket["body"]))
                    rows.append(record(spec, ticket, "train", len(rows)))
                    produced += 1
            print(f"  train [{category}/{language}] {produced}/{per_cell}", file=sys.stderr)


def preload_seen(out_dir: Path, seen: set[str]) -> None:
    """Charge les datasets déjà écrits dans `seen` : une reprise (--only train) ne peut pas
    reproduire un ticket du test → étanchéité train→test préservée entre deux exécutions."""
    for name in ("test.jsonl", "train.jsonl"):
        path = out_dir / name
        if not path.exists():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                seen.add(normalize(json.loads(line)["body"]))
            except (json.JSONDecodeError, KeyError):
                continue


def write_jsonl(rows: list[dict], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        for row in rows:
            f.write(json.dumps(row, ensure_ascii=False) + "\n")


def summarize(rows: list[dict], name: str) -> None:
    print(f"\n{name}: {len(rows)} tickets")
    for field in ("category", "language", "sentiment", "priority"):
        print(f"  {field:9}: {dict(Counter(r[field] for r in rows))}")


# --- Entrée -----------------------------------------------------------------

def main() -> int:
    global THROTTLE_S
    parser = argparse.ArgumentParser(description="Génère train.jsonl / test.jsonl synthétiques.")
    parser.add_argument("--train-n", type=int, default=800)
    parser.add_argument("--test-n", type=int, default=300)
    parser.add_argument("--batch", type=int, default=6, help="tickets par appel LLM")
    parser.add_argument("--gate", default="category,sentiment",
                        help="champs devant concorder au filtre d'accord (test)")
    parser.add_argument("--model", default=None, help="modèle de génération (sinon défaut par fournisseur)")
    parser.add_argument("--judge-model", default=None,
                        help="modèle du filtre d'accord du test set (ex: llama-3.3-70b-versatile). "
                             "Défaut = même que la génération.")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--out-dir", default=str(REPO_ROOT / "eval" / "datasets"))
    parser.add_argument("--only", choices=["train", "test"], default=None)
    parser.add_argument("--check", action="store_true",
                        help="teste chaque fournisseur isolément et sort (diagnostic)")
    parser.add_argument("--sleep", type=float, default=THROTTLE_S,
                        help="pause (s) après chaque appel LLM (rate limit)")
    args = parser.parse_args()
    THROTTLE_S = args.sleep

    load_env(REPO_ROOT / ".env")
    providers = build_providers(args.model)
    judges = build_providers(args.judge_model) if args.judge_model else providers
    print(f"Fournisseurs actifs : {[p['name'] for p in providers]}"
          + (f" | juge : {judges[0]['model']}" if args.judge_model else ""), file=sys.stderr)

    if args.check:
        probe = [{"role": "user", "content": 'Return JSON {"ok": true}'}]
        for prov in providers:
            try:
                complete(probe, [prov], 0.0)   # un seul fournisseur -> erreur isolée
                print(f"OK   {prov['name']} ({prov['model']})")
            except Exception as e:
                print(f"ERR  {prov['name']} ({prov['model']}): {str(e).splitlines()[-1][:280]}")
        return 0
    rng = random.Random(args.seed)
    gate = [f.strip() for f in args.gate.split(",") if f.strip()]
    out_dir = Path(args.out_dir)
    seen: set[str] = set()
    preload_seen(out_dir, seen)  # reprise sûre : ne pas reproduire un ticket déjà écrit

    # Test construit EN PREMIER : ses textes alimentent `seen` -> étanchéité train→test garantie.
    if args.only != "train":
        print("Génération du TEST set (avec filtre d'accord)…", file=sys.stderr)
        test_rows: list[dict] = []
        stats = {"generated": 0, "kept": 0, "rejected": 0, "priority_agree": 0}
        try:
            build_test(test_rows, stats, args.test_n, args.batch, gate, providers, judges, rng, seen)
        except RuntimeError as e:
            print(f"\n⚠ Génération TEST interrompue (budget/fournisseurs) — sauvegarde partielle de "
                  f"{len(test_rows)} tickets. {str(e).splitlines()[0]}", file=sys.stderr)
        write_jsonl(test_rows, out_dir / "test.jsonl")
        summarize(test_rows, "TEST")
        rate = stats["kept"] / stats["generated"] if stats["generated"] else 0
        pagree = stats["priority_agree"] / stats["kept"] if stats["kept"] else 0
        print(f"  filtre d'accord : {stats['kept']} gardés / {stats['generated']} générés "
              f"(taux {rate:.0%}, rejetés {stats['rejected']}) ; accord priorité {pagree:.0%}")

    if args.only != "test":
        print("\nGénération du TRAIN set…", file=sys.stderr)
        train_rows: list[dict] = []
        try:
            build_train(train_rows, args.train_n, args.batch, providers, rng, seen)
        except RuntimeError as e:
            print(f"\n⚠ Génération TRAIN interrompue (budget/fournisseurs) — sauvegarde partielle de "
                  f"{len(train_rows)} tickets. {str(e).splitlines()[0]}", file=sys.stderr)
        write_jsonl(train_rows, out_dir / "train.jsonl")
        summarize(train_rows, "TRAIN")

    print("\nTerminé.", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
