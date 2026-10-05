"""Learned weights for the deterministic feature vector (leave-one-query-out cross-validation).

    python3 scripts/research/score_learned.py
Features (from the Java ResearchRunner, same signals as DeterministicRanker): value, package, dielectric, rating,
tolerance, family (each -1/0/+1), lexical (0..1), stock (0..1), price (0/1), library (0/1).
Models:
  learned_ridge     ridge regression on the 0-3 label (pointwise), lambda=1
  learned_pairwise  linear pairwise logistic (RankNet) on all within-query pairs with different labels, L2=1e-2
Each held-out query is scored by a model fitted on the other 31. The weights fitted on all 32 queries are
written to the meta block for comparison with the hand-set weights.
"""
import numpy as np

from common import load_dataset, load_det_features, write_scores

FEATS = ["value", "package", "dielectric", "rating", "tolerance", "family", "lexical", "stock", "price", "library"]
HAND = {"value": 0.30, "package": 0.20, "dielectric": 0.15, "rating": 0.10, "tolerance": 0.10, "family": 0.05,
        "lexical": 0.10, "stock": 0.03, "price": 0.01, "library": 0.01}


def matrices(data, det):
    X, y, g, keys = [], [], [], []
    for qi, rec in enumerate(data):
        rows = {r["key"]: r for r in det[rec["id"]]["candidates"]}
        for c in rec["candidates"]:
            f = rows[c["key"]]["features"]
            X.append([f[k] for k in FEATS])
            y.append(c["label"])
            g.append(qi)
            keys.append(c["key"])
    return np.array(X, float), np.array(y, float), np.array(g), keys


def fit_ridge(X, y, lam=1.0):
    Xb = np.hstack([X, np.ones((len(X), 1))])
    reg = lam * np.eye(Xb.shape[1])
    reg[-1, -1] = 0
    return np.linalg.solve(Xb.T @ Xb + reg, Xb.T @ y)


def pred_ridge(w, X):
    return np.hstack([X, np.ones((len(X), 1))]) @ w


def fit_pairwise(X, y, g, l2=1e-2, iters=400, lr=0.5):
    di, dj = [], []
    for q in np.unique(g):
        idx = np.where(g == q)[0]
        for a in idx:
            for b in idx:
                if y[a] > y[b]:
                    di.append(a)
                    dj.append(b)
    D = X[di] - X[dj]
    w = np.zeros(X.shape[1])
    for _ in range(iters):
        z = D @ w
        p = 1 / (1 + np.exp(-z))
        grad = -(D.T @ (1 - p)) / len(D) + l2 * w
        w -= lr * grad
    return w


def main():
    data = load_dataset()
    det = load_det_features()
    data = [r for r in data if r["id"] in det]   # det_features.jsonl defines the scored queries
    X, y, g, keys = matrices(data, det)
    out_r, out_p = {}, {}
    for qi, rec in enumerate(data):
        tr, te = g != qi, g == qi
        wr = fit_ridge(X[tr], y[tr])
        wp = fit_pairwise(X[tr], y[tr], g[tr])
        kq = [keys[i] for i in np.where(te)[0]]
        out_r[rec["id"]] = {"scores": dict(zip(kq, pred_ridge(wr, X[te]).tolist())), "latency_ms": 0.0}
        out_p[rec["id"]] = {"scores": dict(zip(kq, (X[te] @ wp).tolist())), "latency_ms": 0.0}
    wr_all = fit_ridge(X, y)
    wp_all = fit_pairwise(X, y, g)
    norm = np.abs(wp_all).sum()
    meta = {"features": FEATS, "hand_weights": HAND,
            "ridge_weights_all": dict(zip(FEATS + ["bias"], np.round(wr_all, 3).tolist())),
            "pairwise_weights_all_l1norm": dict(zip(FEATS, np.round(wp_all / norm, 3).tolist()))}
    write_scores("learned_ridge", out_r, meta)
    write_scores("learned_pairwise", out_p, meta)
    print(meta)


if __name__ == "__main__":
    main()
