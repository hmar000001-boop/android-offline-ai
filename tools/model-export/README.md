# Viddexa Nano model export

The Android runtime does not download models. GitHub Actions downloads one immutable Hugging Face revision, verifies its expected labels/preprocessing contract, exports ONNX, checks the graph, then compares ONNX Runtime CPU logits against PyTorch on two deterministic inputs. The generated ONNX and manifest are packed into the APK assets.

Pinned source:

- Repository: `viddexa/nsfw-detection-2-nano`
- Revision: `12e57200346246b37382f746e4d94d10b014f6a1`
- Model card metadata: Apache-2.0
- Labels: `safe`, `hentai`, `porn`, `sexy`, `drawing`

The exporter does not train or fine-tune the model. The published evaluation metrics are the model publisher's metrics, not FilterBridge benchmark results. The model card says the intended use is explicit nudity and warns that suggestive clothing may be labelled safe; therefore the `sexy` output is an experimental signal only and cannot certify the SUGGESTIVE category as checked.

To run in a prepared Python 3.11 environment:

```bash
python -m pip install --index-url https://download.pytorch.org/whl/cpu torch==2.8.0
python -m pip install -r tools/model-export/requirements.txt
python tools/model-export/export_viddexa_nano.py
```

Generated assets are intentionally not committed: CI creates them from the pinned upstream revision. No model download occurs on the phone.
