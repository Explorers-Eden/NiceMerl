FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY *.py .
COPY data/ data/
COPY assets/thumb_*.png assets/

RUN useradd --create-home bot
USER bot

CMD ["python", "bot.py"]
