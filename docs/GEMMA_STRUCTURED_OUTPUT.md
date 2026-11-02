# Gemma structured output (LiteRT-LM 0.18.0)

ScreenVeil requires machine-readable media boxes. A free-form prompt requesting “JSON only” is insufficient: a response that contains prose, an incomplete object or malformed coordinates cannot be safely mapped to a screen overlay.

## Implementation

The current Kotlin detector:

1. Builds a LiteRT-LM `ConversationConfig` with `enableResponseFormat=true`.
2. Disables thinking for this short visual classification task.
3. Disables speculative decoding before constructing the engine (`ExperimentalFlags.enableSpeculativeDecoding = false`) and in the conversation configuration.
4. Passes `ResponseFormat.json(RESPONSE_SCHEMA)` to `Conversation.sendMessage`.
5. Extracts `Content.Text.text` parts from `Message.contents.contents`.
6. Parses and validates the JSON independently in Kotlin. Invalid responses are not treated as “no media” or “safe”: the service retains only existing localized masks while retrying. After three consecutive model/structured-output failures, it pauses protection, clears the overlay and reports the error. Technical failures never create or retain a full-window cover.

The schema constrains the response to a root object with `regions`; each region has a four-integer normalized `box`, `media_type`, and `decision`. Kotlin additionally checks coordinate range, order, minimum dimensions, a maximum of 24 regions and exact allowed values.

## Why speculative decoding is disabled

LiteRT-LM issue [#3944](https://github.com/google-ai-edge/LiteRT-LM/issues/3944) reports failures when `ResponseFormat.json(...)` is combined with speculative/MTP decoding on Gemma 4 E2B. The reporter measured schema-valid responses when speculative decoding was disabled and unusable results when it was enabled in their test setup. The issue is a report from an individual environment, not a universal benchmark, but it is directly relevant to this model/runtime pairing. ScreenVeil disables speculative decoding to avoid the reported failure mode.

This setting may reduce generation speed. It should not be removed without rerunning structured-output tests on the exact Gemma artifact, LiteRT-LM version and target device.

## What structured output does not guarantee

Constrained decoding helps enforce the JSON syntax and schema. It cannot guarantee that Gemma:

- notices every image or video region;
- classifies every swimsuit correctly;
- recognizes all suggestive presentations;
- returns pixel-accurate boxes;
- responds quickly enough for a particular video.

A valid response may still be visually wrong. In particular, a syntactically valid empty `regions` array or an incorrect `allow` decision can leave media unredacted. The service cannot infer that such an answer is a visual false negative. The Galaxy S24 FE device-validation dataset and measured false-negative/false-positive rates remain mandatory release gates.
