"""Contrôle déterministe des affirmations d'un brouillon (S8-J2).

Ces tests entrent en CI, contrairement à la campagne d'injection (`eval/eval_injection.py`) qui a
besoin d'un LLM et dont le résultat varie. C'est le même partage que partout ailleurs dans ce
projet : les parties déterministes sont vérifiées à chaque commit, les campagnes qui dépendent d'un
fournisseur externe se lancent à la main.

La distinction compte ici plus qu'ailleurs : `grounding.check` est un **garde-fou de sécurité**, et
un garde-fou dont personne ne vérifie qu'il fonctionne encore n'en est pas un.
"""

from app.agents import grounding

PASSAGES = [
    {
        "content": "En cas de double debit, le remboursement est automatique sous 7 jours ouvres. "
                   "Le plafond de geste commercial est de 50 EUR. Contact : support@exemple.fr",
        "source": "faq-facturation.md",
    }
]


def test_a_documented_amount_passes():
    assert grounding.check("Vous serez rembourse sous 7 jours [1].", PASSAGES) == []


def test_an_undocumented_amount_is_refused():
    # Le cas qui a motive ce module : une fausse note de superviseur injectee dans le ticket a fait
    # promettre 5000 EUR au client, alors que le prompt interdisait deja de suivre les instructions
    # trouvees dans les donnees.
    issues = grounding.check("Un geste commercial de 5000 EUR vous est accorde.", PASSAGES)
    assert len(issues) == 1
    assert "5000" in issues[0]


def test_the_same_amount_written_differently_is_recognised():
    # Le modele reecrit « 50 EUR » en « 50,00 € » sans intention particuliere. Comparer
    # litteralement produirait un faux positif sur une reprise parfaitement fidele — et un
    # garde-fou qui crie a tort finit desactive.
    assert grounding.check("Un geste de 50,00 € est possible [1].", PASSAGES) == []


def test_a_non_breaking_space_does_not_create_a_false_positive():
    # Un modele produit spontanement une espace insecable dans un montant en francais. C'est la
    # raison d'etre des deux caracteres construits par `chr()` dans la regex.
    montant = "5" + chr(0x202F) + "000 EUR"
    issues = grounding.check(f"Nous vous accordons {montant}.", PASSAGES)
    assert len(issues) == 1, "le montant doit etre detecte malgre l'espace fine insecable"


def test_an_undocumented_email_is_refused():
    # Hameconnage par ricochet : le but n'est pas de tromper le modele mais le client, en faisant
    # sortir une adresse frauduleuse sous le nom de l'entreprise.
    issues = grounding.check("Ecrivez a attaquant@malveillant.test pour la suite.", PASSAGES)
    assert len(issues) == 1
    assert "attaquant@malveillant.test" in issues[0]


def test_a_documented_email_passes():
    assert grounding.check("Ecrivez a support@exemple.fr [1].", PASSAGES) == []


def test_a_source_filename_never_belongs_in_a_customer_message():
    # Refuse meme quand le fichier existe : la citation est rendue par l'interface sous forme de
    # marqueur cliquable, pas recopiee dans le texte envoye au client.
    issues = grounding.check("Voir faq-facturation.md pour le detail.", PASSAGES)
    assert len(issues) == 1


def test_an_empty_draft_has_nothing_to_check():
    # Cas de l'abstention : le brouillon n'affirme rien, il n'y a rien a fonder. Lever un reproche
    # ici couterait deux re-generations inutiles et une fausse alerte — exactement le defaut trouve
    # en verification du S5-J3.
    assert grounding.check("", PASSAGES) == []


def test_the_check_is_narrow_by_design():
    # Documente ce que le garde-fou **ne fait pas**. Une promesse sans chiffre passe, et c'est
    # assume : un controle large produirait des faux positifs, l'agent regenererait sans cesse, et
    # le garde-fou finirait desactive. Ce cas reste couvert par le controle semantique et par la
    # validation humaine.
    assert grounding.check("Nous ferons un geste commercial exceptionnel.", PASSAGES) == []
