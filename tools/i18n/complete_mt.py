#!/usr/bin/env python3
"""Fill only missing Android XML strings with OFFLINE, MIT-licensed machine translation.

One-time localization generator, NOT a runtime dependency and NOT a Firebase
service. Model: entai2965/m2m100-418M-ctranslate2 (MIT). The generated texts
are draft translations until a qualified native reviewer validates vehicle-
specific safety warnings. Existing human translations are never overwritten.
"""
from __future__ import annotations
import argparse
from collections import OrderedDict
import html
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
MODEL = "entai2965/m2m100-418M-ctranslate2"
LANGS = ("bg", "hr", "cs", "da", "et", "fi", "hu", "ga", "lv", "lt",
         "mt", "pl", "ro", "sk", "sl", "sv")
STRING = re.compile(r'<string\s+name="([^"]+)"[^>]*>([\s\S]*?)</string>')
PRINTF = re.compile(r'%([1-9]\d*\$)?[-+# 0,(]*\d*(?:\.\d+)?[a-zA-Z]')
PROTECTED = re.compile(
    r'(%(?:[1-9]\d*\$)?[-+# 0,(]*\d*(?:\.\d+)?[a-zA-Z]'
    r'|%%|\\n|\\t|\\r|<[^>]+>|&(?:amp|lt|gt|quot|apos);)'
)


def read_strings(path: pathlib.Path) -> OrderedDict[str, str]:
    raw = path.read_text(encoding="utf-8")
    ET.fromstring(raw)  # reject malformed resource files before any translation
    pairs = STRING.findall(raw)
    if len(pairs) != len({key for key, _ in pairs}):
        raise ValueError("Duplicate <string> key in " + str(path))
    return OrderedDict(pairs)


def segments(value: str) -> list[tuple[bool, str]]:
    """Protect Android printf, XML markup, escapes and line breaks exactly."""
    out = []
    for index, token in enumerate(PROTECTED.split(value)):
        if not token:
            continue
        if index % 2 == 1:
            out.append((False, token))
        else:
            # Decode XML entities only in plain source text. Keep markup intact.
            clean = html.unescape(token.replace("\\'", "'").replace('\\"', '"'))
            out.append((True, clean))
    return out


def translate_missing(language: str, model_path: str, output_root: pathlib.Path):
    import ctranslate2
    import transformers

    base = read_strings(RES / "values/strings.xml")
    target_path = RES / ("values-" + language) / "strings.xml"
    existing = read_strings(target_path)
    missing = OrderedDict((key, value) for key, value in base.items()
                          if key not in existing)
    print(f"{language}: {len(missing)} missing strings", flush=True)
    if not missing:
        return

    translator = ctranslate2.Translator(
        model_path, device="cpu", compute_type="auto",
        inter_threads=1, intra_threads=4
    )
    tokenizer = transformers.AutoTokenizer.from_pretrained(
        model_path, clean_up_tokenization_spaces=False,
        trust_remote_code=False
    )
    tokenizer.src_lang = "en"
    target_token = tokenizer.lang_code_to_token[language]

    by_key = {key: segments(value) for key, value in missing.items()}
    unique_segments: OrderedDict[str, str] = OrderedDict()
    for pieces in by_key.values():
        for is_text, segment in pieces:
            if is_text and segment.strip() and any(letter.isalpha() for letter in segment):
                unique_segments.setdefault(segment, "")
    phrases = list(unique_segments)
    print(f"{language}: {len(phrases)} distinct translatable pieces", flush=True)

    # Translate in batches using the locally loaded open model. This does not
    # issue ANY per-string remote request, unlike unofficial translation APIs.
    for start in range(0, len(phrases), 24):
        batch = phrases[start:start + 24]
        source = [tokenizer.convert_ids_to_tokens(tokenizer.encode(text))
                  for text in batch]
        results = translator.translate_batch(
            source,
            target_prefix=[[target_token] for _ in batch],
            beam_size=1,
            max_decoding_length=192
        )
        for phrase, result in zip(batch, results, strict=True):
            target_ids = tokenizer.convert_tokens_to_ids(result.hypotheses[0][1:])
            translated = tokenizer.decode(
                target_ids, skip_special_tokens=True
            ).strip()
            if not translated:
                raise ValueError(f"Blank translation in {language}: {phrase!r}")
            unique_segments[phrase] = translated
        if start % 120 == 0:
            print(f"{language}: processed {min(start+24, len(phrases))}/{len(phrases)}",
                  flush=True)

    def escape_android(value: str) -> str:
        # Android string resources require escaped apostrophes, even in XML.
        return (value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', '&quot;')
                .replace("'", "\\'"))

    lines = []
    for key, src in missing.items():
        output = []
        for is_text, part in by_key[key]:
            if not is_text:
                output.append(part)
            elif part in unique_segments:
                output.append(escape_android(unique_segments[part]))
            else:
                # Empty text / punctuation / technical numeric constants.
                output.append(escape_android(part))
        rendered = "".join(output)
        expected = PRINTF.findall(src.replace("%%", ""))
        actual = PRINTF.findall(rendered.replace("%%", ""))
        if expected != actual:
            raise ValueError(
                f"Printf argument mismatch {language}/{key}: {expected} vs {actual}"
            )
        if not rendered.strip():
            raise ValueError(f"Empty generated resource {language}/{key}")
        lines.append(f'    <string name="{key}">{rendered}</string>')

    # Do not touch existing translation lines. Assemble into a separate folder
    # for artifact upload; only the aggregate job commits all 16 as one lot.
    original = target_path.read_text(encoding="utf-8")
    if original.count("</resources>") != 1:
        raise ValueError("Expected exactly one Android resources root")
    result = original.replace(
        "</resources>",
        "\n    <!-- Machine-generated draft: native review required before release -->\n" +
        "\n".join(lines) + "\n</resources>"
    )
    ET.fromstring(result)
    final_keys = read_strings_text(result)
    if set(final_keys) != set(base):
        raise ValueError(f"Locale coverage mismatch after generation for {language}")
    generated = output_root / ("values-" + language) / "strings.xml"
    generated.parent.mkdir(parents=True, exist_ok=True)
    generated.write_text(result, encoding="utf-8")
    print(f"{language}: complete {len(final_keys)}/{len(base)} string keys", flush=True)


def read_strings_text(source: str) -> OrderedDict[str, str]:
    pairs = STRING.findall(source)
    if len(pairs) != len({key for key, _ in pairs}):
        raise ValueError("Duplicate generated resource key")
    return OrderedDict(pairs)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--lang", required=True, choices=LANGS)
    parser.add_argument("--output", default="/tmp/camarodiag-locales")
    options = parser.parse_args()
    from huggingface_hub import snapshot_download

    model_dir = snapshot_download(MODEL)
    translate_missing(options.lang, model_dir, pathlib.Path(options.output))
