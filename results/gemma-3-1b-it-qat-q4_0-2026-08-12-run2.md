# Prompt Suite — MODEL mode

model: `gemma-3-1b-it-qat-q4_0`
cases: 35 of 35
passed: 7 (20.0%)
grammar violations: 0
latency: median 10809ms · p90 16362ms
mean tokens: prompt 643 · output 57

| category | passed | of |
|---|---:|---:|
| ambig | 0 | 2 |
| anaph | 1 | 2 |
| arrange | 0 | 2 |
| connect | 0 | 3 |
| create | 0 | 3 |
| delete | 1 | 4 |
| fail | 1 | 4 |
| find | 1 | 2 |
| modify | 2 | 3 |
| move | 1 | 4 |
| multi | 0 | 4 |
| setting | 0 | 2 |

| case | result | ms | tok/s | detail |
|---|---|---:|---:|---|
| create-01 | fail | 18691 | 6.3 | outcome was REJECTED, expected OK (step 1 argument 'node' refers to $1, which has not run yet); expected 1 nodes, found 0; no node labelled 'Village' (have: []) |
| create-02 | fail | 8007 | 5.8 | no node labelled 'grappling hook' (have: []); expected 1 nodes, found 0 |
| create-03 | fail | 15584 | 6.1 | expected 4 nodes, found 3; no node labelled 'Castle' (have: [Village, Tavern, Borin]) |
| multi-01 | fail | 9891 | 6.4 | expected 3 nodes, found 1; no node labelled 'Tavern' (have: [village]); no node labelled 'Borin' (have: [village]); no node labelled 'Village'; no node labelled |
| multi-02 | fail | 7154 | 6.0 | outcome was REJECTED, expected OK (step 1 argument 'node' refers to $1, which has not run yet); expected 4 nodes, found 0 |
| multi-03 | fail | 8851 | 6.0 | expected 5 nodes, found 3; no node labelled 'Forest' (have: [Village, Tavern, Borin]); no node labelled 'River' (have: [Village, Tavern, Borin]) |
| multi-04 | fail | 7385 | 5.8 | outcome was REJECTED, expected OK (step 1 argument 'node' refers to $1, which has not run yet); expected 5 nodes, found 0 |
| modify-01 | fail | 14018 | 4.8 | 'Borin'.secret is null, expected 'vampire' |
| modify-02 | pass | 9262 | 5.2 |  |
| modify-03 | pass | 9566 | 5.3 |  |
| move-02 | fail | 8692 | 5.9 | 'Castle' is at r2c2, expected r-1c0; 'Castle' at r2c2 is not north of 'Village' at r0c0 |
| move-03 | pass | 7923 | 6.0 |  |
| move-04 | fail | 10809 | 6.1 | 'Tavern' at r0c1 is not north of 'Village' at r0c0 |
| connect-02 | fail | 14673 | 6.1 | no CONNECTS edge from 'Tavern' to 'Borin' |
| connect-03 | fail | 12880 | 4.6 | no OWNS edge from 'Borin' to 'Tavern' |
| connect-01 | fail | 10110 | 4.9 | 'Borin'.afraid_of is null, expected 'frogs' |
| delete-01 | fail | 10492 | 5.4 | outcome was AWAITING_CONFIRMATION, expected OK (); 'Tavern' should have been removed; expected 2 nodes, found 3 |
| delete-02 | pass | 15159 | 5.5 |  |
| delete-02b | fail | 15847 | 5.2 | outcome was PARTIAL, expected OK (no node called n2) |
| delete-03 | fail | 15213 | 5.3 | expected AWAITING_CONFIRMATION, got OK; expected no board change, got 1 deltas |
| anaph-01 | pass | 9798 | 5.5 |  |
| anaph-02 | fail | 9285 | 4.9 | 'Castle' at r2c2 is not west of 'Village' at r1c1 |
| arrange-01 | fail | 12878 | 5.5 | expected one row, found [1, 9] |
| arrange-02 | fail | 11017 | 5.0 | 'Idea A' at r0c5 is not west of 'Village' at r0c0 |
| setting-01 | fail | 8931 | 5.4 | expected model.temperature=0.9, got [] |
| setting-02 | fail | 10932 | 5.1 | expected agent.confirm_threshold=20, got [] |
| fail-01 | fail | 8416 | 5.4 | outcome was OK, expected PARTIAL (); no node labelled 'Keep' (have: [Village, Tavern, Borin]) |
| fail-02 | fail | 35483 | 4.7 | outcome was OK, expected REJECTED (); expected no board change, got 3 deltas |
| fail-03 | fail | 16362 | 5.0 | outcome was AWAITING_CONFIRMATION, expected REJECTED (); failure code was null, expected GRAMMAR_VIOLATION |
| fail-04 | pass | 7791 | 5.7 |  |
| find-01 | fail | 12945 | 4.9 | 'Dragon'.mood is null, expected 'angry' |
| find-02 | pass | 21987 | 4.6 |  |
| ambig-01 | fail | 13666 | 4.1 | expected the model to respond; expected no board change, got 1 deltas |
| ambig-02 | fail | 25021 | 4.9 | expected the model to respond |
| move-01 | fail | 9115 | 5.3 | expected the model to respond |
