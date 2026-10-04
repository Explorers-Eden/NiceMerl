"""Meaning-based search with a tiny static embedding model (Model2Vec potion-base-8M).

The model is a table of word vectors: a text's vector is the average of its words' vectors, so it
understands that "unlock the boss room" is close to "boss key" without any AI runtime. It only
compares texts, it never writes any. Used next to the keyword search in search.py (hybrid search),
and to pick the sentences of the short answer. Optional: without the model Merl searches by keywords.
"""

import logging
from pathlib import Path

log = logging.getLogger("nicemerl")

# The model on Hugging Face: about 30 MB, 256 numbers per text.
MODEL_NAME = "minishlab/potion-base-8M"


class Embedder:
    """Turns texts into unit-length vectors; cosine similarity is then a plain dot product."""

    def __init__(self, model):
        import numpy as np

        self.np = np
        self.model = model

    def __call__(self, texts: list[str]):
        vectors = self.model.encode(texts)
        norms = self.np.linalg.norm(vectors, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        return vectors / norms


def load(source: str | Path) -> Embedder | None:
    """The model from a local folder (baked into the Docker image) or a Hugging Face name, or None
    when it isn't available, in which case Merl just uses keyword search."""
    if not source:
        return None
    try:
        from model2vec import StaticModel

        return Embedder(StaticModel.from_pretrained(str(source)))
    except Exception:
        log.warning("Meaning-based search is off: could not load the model from %s", source, exc_info=True)
        return None
