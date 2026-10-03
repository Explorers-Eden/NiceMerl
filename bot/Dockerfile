FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY *.py .
COPY data/ data/
COPY assets/merl_* assets/thumb_* assets/

# state/ holds Peanut Butter's pet count; mount a volume there to keep it across updates.
RUN useradd --create-home bot && mkdir state && chown bot state
USER bot

CMD ["python", "bot.py"]
