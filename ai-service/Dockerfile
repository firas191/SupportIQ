# syntax=docker/dockerfile:1.7
FROM python:3.12-slim
WORKDIR /srv

# Bibliotheques systeme de WeasyPrint (rendu PDF du digest, S6-J4). Ce sont des dependances
# **natives** : `pip install weasyprint` reussit sans elles, et l'import echoue a l'execution.
# C'est pourquoi `digest_render.to_pdf` degrade proprement au lieu de supposer leur presence.
# `fonts-dejavu-core` : sans police installee, le PDF sort avec des carres a la place des accents.
RUN --mount=type=cache,target=/var/cache/apt \
    apt-get update && apt-get install -y --no-install-recommends \
        libpango-1.0-0 libpangoft2-1.0-0 libharfbuzz0b libffi8 fonts-dejavu-core \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .

# Installation des dependances avec un **cache de roues partage entre builds**.
#
# Probleme resolu (constate en S6-J1) : `--no-cache-dir` interdisait a pip de conserver les roues
# telechargees. Ajouter une seule ligne a requirements.txt invalidait la couche, et pip
# re-telechargeait l'integralite des dependances — dont torch, tire par sentence-transformers, soit
# pres de 2 Go. Sur une connexion ordinaire, 34 minutes puis un timeout.
#
# `--mount=type=cache` monte un cache persistant du demon Docker, **hors image finale** : les roues
# sont conservees d'un build a l'autre sans alourdir l'image d'un octet. Ajouter un petit paquet ne
# retelecharge plus que lui.
#
# `--timeout` et `--retries` : PyPI sert des fichiers de plusieurs centaines de Mo, et le defaut de
# 15 s abandonne au premier ralentissement. Une coupure reseau ne doit pas obliger a tout reprendre.
RUN --mount=type=cache,target=/root/.cache/pip \
    pip install --timeout 120 --retries 10 -r requirements.txt

COPY app ./app
EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001", "--reload"]
