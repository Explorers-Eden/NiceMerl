FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
# The meaning-based search model goes into the image, so the bot never downloads anything at runtime.
RUN python -c "from model2vec import StaticModel; StaticModel.from_pretrained('minishlab/potion-base-8M').save_pretrained('/app/model')" \
    && chmod -R a+rX /app/model

COPY *.py .
COPY data/ data/
COPY assets/merl_* assets/thumb_* assets/

# state/ holds Peanut Butter's pet count and friends.json (what Merl remembers about people); mount a volume there to keep it across updates.
RUN useradd --create-home bot && mkdir state && chown bot state
USER bot

CMD ["python", "bot.py"]
