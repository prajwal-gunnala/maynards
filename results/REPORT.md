# What each configuration is worth

The same 8 coding problems, asked of every setup, scored by running the
answers against their tests. No partial credit, no human judgement.

| setup | accuracy | passed | median speed | median per task | total | measured |
|---|---|---|---|---|---|---|
| phone-class model, 0.6B | **25.0%** | 2/8 | 22.1 tok/s | 5.8 s | 44.4 s | 2026-09-27 00:39 |
| bigger model, 1.7B | **62.5%** | 5/8 | 9.0 tok/s | 12.6 s | 96.4 s | 2026-09-27 00:52 |

![accuracy](accuracy.svg)

## Conditions

- **phone-class model, 0.6B**: Qwen3-0.6B Q8_0 on an i3 laptop, stands in for what a phone holds alone
- **bigger model, 1.7B**: Qwen3-1.7B Q8_0, same laptop, same tasks

## Failures

**phone-class model, 0.6B** failed 6: running_max (IndexError: list index out of range), balanced_brackets (SyntaxError: unterminated string literal (detected at line 3)), word_frequencies (AssertionError), roman_to_int (AssertionError), flatten_nested (TypeError: 'int' object is not iterable), group_anagrams (AssertionError)

**bigger model, 1.7B** failed 3: word_frequencies (AssertionError), merge_intervals (AssertionError), group_anagrams (AssertionError)
