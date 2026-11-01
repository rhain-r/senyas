# Results tables

These tables summarise the evaluation runs behind the app's built-in references.
They were written by the pipeline scripts from stored run outputs; no number was typed in by hand except in `design_ablation_9_signs.csv`.

| File | Written by | Content |
|---|---|---|
| `evaluation_global_k.csv` | `07_evaluate.py --aggregate-only --csv` | Leave-one-signer-out pass rates per sign at one global K = 1.25 (pass = score >= 85). |
| `threshold_rules.csv` | `08_threshold_study.py analyze --csv-dir` | Overall pass rates under 5 global-K rules and 15 contrast rules. |
| `per_sign_pass_k.csv` | `08_threshold_study.py analyze --csv-dir` | Per-sign pass rates and per-fold K under the shipped rule, contrast(1.25, 1.8). |
| `pass_thresholds.csv` | `04_pass_thresholds.py --csv` | The shipped passK of each sign, with the other sign's clip that comes closest. |
| `design_ablation_9_signs.csv` | by hand, from two runs of `07_evaluate.py` | The 9 original signs scored without and with the direction parameter. |

## Column meanings

- `P`, `N`, `H`, `E`, `R` are pass rates (0-1) of the five probe kinds; see Section 8 of `../README.md`.
- `n_P` ... `n_R` are probe counts; the `ALL` row weights every sign by its probe counts.
- `K_fold_A` ... `K_fold_D` are the thresholds used when signer A, B, C or D is held out.
- `x_closest` is the closest distance, in tolerance units, of any other sign's bundled clip.

## Note on the two pass criteria

`07_evaluate.py` passes a probe at a rounded score of 85 or more, as the app does.
`08_threshold_study.py` uses the unrounded form x <= K, so it is slightly stricter (0.763 vs 0.777 P at K = 1.25).
