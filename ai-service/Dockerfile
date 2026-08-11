# syntax=docker/dockerfile:1.7
FROM python:3.12-slim
WORKDIR /srv
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
