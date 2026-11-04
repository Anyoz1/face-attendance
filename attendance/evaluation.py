"""Frame-level evaluation on a separate capture set, including unknown people."""


def evaluate(gallery, probes, policy):
    totals = dict(known=0, unknown=0, correct_known=0, wrong_identity=0,
                  rejected_known=0, false_accept_unknown=0, correct_reject_unknown=0,
                  unusable_images=0)
    for expected, embedding in probes:
        result = gallery.identify(embedding, policy) if embedding is not None else None
        totals['unusable_images'] += embedding is None
        if expected is None:
            totals['unknown'] += 1
            totals['false_accept_unknown' if result else 'correct_reject_unknown'] += 1
        else:
            totals['known'] += 1
            if result is None:
                totals['rejected_known'] += 1
            else:
                totals['correct_known' if result['id'] == expected else 'wrong_identity'] += 1
    for name, numerator, denominator in (
        ('wrong_identity_rate', 'wrong_identity', 'known'),
        ('known_rejection_rate', 'rejected_known', 'known'),
        ('unknown_false_accept_rate', 'false_accept_unknown', 'unknown'),
    ):
        totals[name] = totals[numerator] / totals[denominator] if totals[denominator] else None
    return totals
