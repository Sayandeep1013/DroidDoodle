# Prompt Suite — MODEL mode

model: `gemma-3-1b-it-qat-q4_0`
cases: 35 of 35
passed: 7 (20.0%)
grammar violations: 0
latency: median 11432ms · p90 19934ms
mean tokens: prompt 643 · output 61

| category | passed | of |
|---|---:|---:|
| ambig | 0 | 2 |
| anaph | 1 | 2 |
| arrange | 0 | 2 |
| connect | 0 | 3 |
| create | 1 | 3 |
| delete | 1 | 4 |
| fail | 0 | 4 |
| find | 1 | 2 |
| modify | 2 | 3 |
| move | 1 | 4 |
| multi | 0 | 4 |
| setting | 0 | 2 |

| case | result | ms | tok/s | detail |
|---|---|---:|---:|---|
| create-01 | pass | 31074 | 5.5 |  |
| create-02 | fail | 10706 | 5.3 | 'grappling hook' is GROUP, expected NOTE |
| create-03 | fail | 19934 | 4.8 | expected 4 nodes, found 3; no node labelled 'Castle' (have: [Village, Tavern, Borin]) |
| multi-01 | fail | 11482 | 5.6 | expected 3 nodes, found 1; no node labelled 'Tavern' (have: [village]); no node labelled 'Borin' (have: [village]); no node labelled 'Tavern'; no node labelled  |
| multi-02 | fail | 11837 | 5.7 | expected 4 nodes, found 1 |
| multi-03 | fail | 8361 | 6.5 | expected 5 nodes, found 3; no node labelled 'Forest' (have: [Village, Tavern, Borin]); no node labelled 'River' (have: [Village, Tavern, Borin]) |
| multi-04 | fail | 11187 | 5.7 | expected 5 nodes, found 1 |
| modify-01 | fail | 14270 | 4.6 | 'Borin'.secret is null, expected 'vampire' |
| modify-02 | pass | 9972 | 4.8 |  |
| modify-03 | pass | 10623 | 4.8 |  |
| move-02 | fail | 8849 | 5.8 | 'Castle' is at r2c2, expected r-1c0; 'Castle' at r2c2 is not north of 'Village' at r0c0 |
| move-03 | pass | 8121 | 5.9 |  |
| move-04 | fail | 11372 | 5.3 | 'Tavern' at r0c1 is not north of 'Village' at r0c0 |
| connect-02 | fail | 15919 | 5.5 | no CONNECTS edge from 'Tavern' to 'Borin' |
| connect-03 | fail | 13405 | 4.4 | no OWNS edge from 'Borin' to 'Tavern' |
| connect-01 | fail | 9411 | 5.3 | 'Borin'.afraid_of is null, expected 'frogs' |
| delete-01 | fail | 10570 | 5.3 | outcome was AWAITING_CONFIRMATION, expected OK (); 'Tavern' should have been removed; expected 2 nodes, found 3 |
| delete-02 | pass | 16526 | 5.1 |  |
| delete-02b | fail | 15245 | 5.4 | outcome was PARTIAL, expected OK (no node called n2) |
| delete-03 | fail | 16280 | 5.0 | expected AWAITING_CONFIRMATION, got OK; expected no board change, got 1 deltas |
| anaph-01 | pass | 11432 | 4.7 |  |
| anaph-02 | fail | 8899 | 5.2 | 'Castle' at r2c2 is not west of 'Village' at r1c1 |
| arrange-01 | fail | 12965 | 5.1 | expected one row, found [1, 9] |
| arrange-02 | fail | 11012 | 5.4 | 'Idea A' at r0c5 is not west of 'Village' at r0c0 |
| setting-01 | fail | 8673 | 5.4 | expected model.temperature=0.9, got [] |
| setting-02 | fail | 11012 | 5.1 | expected agent.confirm_threshold=20, got [] |
| fail-01 | fail | 8175 | 5.5 | outcome was OK, expected PARTIAL (); no node labelled 'Keep' (have: [Village, Tavern, Borin]) |
| fail-02 | fail | 32749 | 5.1 | outcome was OK, expected REJECTED (); expected no board change, got 3 deltas |
| fail-03 | fail | 15907 | 5.1 | outcome was AWAITING_CONFIRMATION, expected REJECTED (); failure code was null, expected GRAMMAR_VIOLATION |
| fail-04 | fail | 10776 | 5.2 | outcome was OK, expected REJECTED (); failure code was null, expected STATIC_VALIDATION; expected 0 nodes, found 1 |
| find-01 | fail | 13162 | 5.1 | 'Dragon'.mood is null, expected 'angry' |
| find-02 | pass | 20458 | 4.9 |  |
| ambig-01 | fail | 12936 | 4.4 | expected the model to respond; expected no board change, got 1 deltas |
| ambig-02 | fail | 23242 | 5.3 | expected the model to respond |
| move-01 | fail | 9925 | 5.2 | expected the model to respond |
