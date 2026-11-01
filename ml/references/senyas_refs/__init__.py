"""Senyas reference pipeline: builds the app's built-in FSL sign references from FSL-105.

Modules:
  config        default input/output locations ($SENYAS_DATA, $SENYAS_OUT)
  kit           the 26 app signs, landmark loading, probes, leave-one-signer-out protocol
  sign_scorer   the scoring design that the app's SignScorer.java reproduces
"""
