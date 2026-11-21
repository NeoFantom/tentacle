"""Send alerts and manage forced-alert questions on the phone."""
from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys
import time
import unicodedata
import uuid

from .phone import PhoneClient, PhoneError


class QuestionGenerationError(Exception):
    pass


def _wait_seconds(value: str) -> int:
    try:
        seconds = int(value)
    except ValueError:
        raise ValueError("--wait-ack must be a nonnegative number of seconds") from None
    if seconds < 0:
        raise ValueError("--wait-ack must be a nonnegative number of seconds")
    return seconds


def _question_count(value: str) -> int:
    try:
        count = int(value)
    except ValueError:
        raise ValueError("--count must be a positive integer") from None
    if count < 1 or count > 150:
        raise ValueError("--count must be between 1 and 150 per language")
    return count


def add_commands(subparsers) -> None:
    parser = subparsers.add_parser("notify", help="notify the phone owner")
    parser.add_argument("text", metavar="TEXT",
                        help="notification text, or 'questions' for question management")
    parser.add_argument("question_args", metavar="ARG", nargs="*", help="arguments for 'notify questions'")
    parser.add_argument("--title", metavar="T", help="notification title")
    parser.add_argument("--level", choices=("normal", "ring", "forced"),
                        help="alert strength (default: normal)")
    parser.add_argument("--urgent", action="store_true", help="alias for --level ring")
    parser.add_argument("--wait-ack", nargs="?", const=300, type=_wait_seconds,
                        metavar="SECONDS", help="wait for acknowledgement (default: 300 seconds)")
    parser.add_argument("--json", action="store_true", help="print the phone response as JSON")
    parser.add_argument("--count", type=_question_count, default=30, metavar="N",
                        help="questions to generate per language (default: 30)")
    parser.add_argument("--out", metavar="FILE", help="write generated question JSON")
    parser.add_argument("--no-push", action="store_true", help="generate without updating the phone")
    parser.set_defaults(func=_run)


def _delivery_message(result: dict) -> str:
    if not result["shown"]:
        if result.get("fallback") == "urgent":
            return ("phone app does not support forced alerts; ring alert not shown: "
                    f"{result.get('reason', 'unknown')}")
        return f"not shown: {result.get('reason', 'unknown')}"
    if result.get("reason") == "urgent_not_allowed":
        if result.get("fallback") == "urgent":
            return ("phone app does not support forced alerts; ring alerts are off in the app, "
                    "sent as a normal alert")
        return "delivered (ring alerts are off in the app, sent as a normal alert)"
    if result.get("reason") == "forced_not_allowed":
        sent_as = "ring" if result["level"] == "urgent" else "normal"
        return f"delivered (forced alerts are off in the app, sent as a {sent_as} alert)"
    if result.get("reason") == "full_screen_not_allowed":
        if result.get("fallback") == "urgent":
            return ("phone app does not support forced alerts; sent as a ring alert "
                    "(full-screen alerts not allowed on the phone)")
        return "delivered (full-screen alerts not allowed on the phone)"
    if result.get("fallback") == "urgent":
        return "phone app does not support forced alerts; sent as a ring alert"
    return "delivered"


def _validate_text(text: str, title: str | None) -> None:
    if not 1 <= len(text) <= 1000:
        raise ValueError("TEXT must contain 1 to 1000 characters")
    if title is not None and len(title) > 100:
        raise ValueError("--title must contain at most 100 characters")
    for value in (text, title):
        if value is not None and any(
                character != "\n" and unicodedata.category(character) == "Cc"
                for character in value):
            raise ValueError("notification text must not contain control characters")


def _post_with_retry(client: PhoneClient, *, text: str, title: str | None,
                     level: str, notification_id: str) -> dict:
    wire_level = "urgent" if level == "ring" else level

    def post(send_level: str) -> dict:
        return client.send_notification(
            text, notification_id=notification_id, title=title, level=send_level,
        )

    def post_with_transport_retry(send_level: str) -> dict:
        try:
            return post(send_level)
        except PhoneError as exc:
            if exc.code not in {"timeout", "unreachable"}:
                raise
        return post(send_level)

    try:
        result = post_with_transport_retry(wire_level)
    except PhoneError as exc:
        if level != "forced" or exc.status != 400:
            raise
        result = post_with_transport_retry("urgent")
        result["fallback"] = "urgent"
    return result


def _run(args, cfg) -> int:
    if args.text == "questions":
        return _run_questions(args, cfg)
    try:
        if args.question_args:
            raise ValueError("notification TEXT must be one argument")
        if args.no_push or args.out is not None or args.count != 30:
            raise ValueError("--count, --out and --no-push are only for question generation")
        if args.urgent and args.level is not None:
            raise ValueError("--urgent and --level cannot be used together")
        level = "ring" if args.urgent else (args.level or "normal")
        _validate_text(args.text, args.title)
        client = PhoneClient(cfg.phone, cfg.data_dir)
        result = _post_with_retry(client, text=args.text, title=args.title,
                                  level=level, notification_id=str(uuid.uuid4()))
        if args.json:
            print(json.dumps(result, ensure_ascii=False))
        else:
            print(_delivery_message(result))
        if not result["shown"]:
            return 1
        if args.wait_ack is None:
            return 0

        deadline = time.monotonic() + args.wait_ack
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                break
            wait = min(55, max(1, int(remaining + 0.999)))
            poll_started = time.monotonic()
            state = client.notification(result["id"], wait=wait)
            if state["state"] in {"acknowledged", "dismissed"}:
                if args.json:
                    print(json.dumps(state, ensure_ascii=False))
                else:
                    print(state["state"])
                return 0
            if state["state"] == "timeout":
                if args.json:
                    print(json.dumps(state, ensure_ascii=False))
                print("forced alert stopped after its time limit without an answer", file=sys.stderr)
                return 1
            if state["state"] == "expired":
                break
            if time.monotonic() - poll_started < 0.1:
                time.sleep(min(0.1, max(0.0, deadline - time.monotonic())))
        print("no response before timeout", file=sys.stderr)
        return 1
    except PhoneError as exc:
        return _phone_error(exc)
    except ValueError as exc:
        print(f"usage/config error: {exc}", file=sys.stderr)
        return 2


def _run_questions(args, cfg) -> int:
    try:
        if args.title is not None or args.level is not None or args.urgent or args.wait_ack is not None or args.json:
            raise ValueError("notification options cannot be used with 'notify questions'")
        command = args.question_args[0] if args.question_args else "show"
        rest = args.question_args[1:]
        if command in {"show", "clear"}:
            if rest:
                raise ValueError(f"questions {command} takes no arguments")
            if args.no_push or args.out is not None or args.count != 30:
                raise ValueError("generation options require 'questions generate'")
            client = PhoneClient(cfg.phone, cfg.data_dir)
            summary = client.notify_questions() if command == "show" else client.put_notify_questions([])
        elif command == "push":
            if len(rest) != 1:
                raise ValueError("questions push requires FILE")
            if args.no_push or args.out is not None or args.count != 30:
                raise ValueError("generation options require 'questions generate'")
            questions = _read_question_file(Path(rest[0]))
            summary = PhoneClient(cfg.phone, cfg.data_dir).put_notify_questions(questions)
        elif command == "generate":
            if rest:
                raise ValueError("questions generate takes no positional arguments")
            questions = _generate_questions(cfg.notify, args.count)
            document = {"questions": questions}
            if args.out is not None:
                _write_questions(Path(args.out), document)
            if args.no_push:
                _print_local_summary(questions, args.out)
                return 0
            summary = PhoneClient(cfg.phone, cfg.data_dir).put_notify_questions(questions)
        else:
            raise ValueError("questions command must be show, push, clear or generate")
        _print_summary(summary)
        return 0
    except PhoneError as exc:
        if exc.status in {404, 405}:
            print("the phone app is too old for custom questions", file=sys.stderr)
            return 1
        return _phone_error(exc)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        print(f"question file error: {exc}", file=sys.stderr)
        return 2
    except ValueError as exc:
        print(f"usage/config error: {exc}", file=sys.stderr)
        return 2
    except QuestionGenerationError as exc:
        print(f"question generation failed: {exc}", file=sys.stderr)
        return 1


def _phone_error(exc: PhoneError) -> int:
    if exc.status == 429 or exc.code == "rate_limited":
        print(f"rate limited: {exc.message}", file=sys.stderr)
        return 1
    print(f"Phone notification failed ({exc.code}).", file=sys.stderr)
    return 2 if exc.code in {"not_paired", "unauthorized"} else 1


def _normal_answer(value: str) -> str:
    normalized = unicodedata.normalize("NFKC", value).strip().lower()
    normalized = " ".join(normalized.split())
    return normalized.rstrip(".。!！").rstrip()


def _control_free(value: str) -> bool:
    return not any(unicodedata.category(character) == "Cc" for character in value)


def _valid_question(item: object) -> dict | None:
    if not isinstance(item, dict) or set(item) - {"lang", "q", "answers", "domain"}:
        return None
    lang, prompt, answers = item.get("lang"), item.get("q"), item.get("answers")
    domain = item.get("domain")
    if not isinstance(lang, str) or lang not in {"en", "zh"}:
        return None
    if not isinstance(prompt, str) or not 1 <= len(prompt) <= 200:
        return None
    if not _control_free(prompt) or not isinstance(answers, list) or not 1 <= len(answers) <= 10:
        return None
    if "domain" in item and (not isinstance(domain, str) or len(domain) > 30 or not _control_free(domain)):
        return None
    clean_answers: list[str] = []
    for answer in answers:
        if (not isinstance(answer, str) or not 1 <= len(answer) <= 60
                or not _control_free(answer) or not _normal_answer(answer)):
            return None
        clean_answers.append(answer)
    result: dict = {"lang": lang, "q": prompt, "answers": clean_answers}
    if "domain" in item:
        result["domain"] = domain
    return result


def _validated_questions(value: object, *, drop_invalid: bool, dedupe: bool = False) -> list[dict]:
    if not isinstance(value, list):
        raise ValueError("questions must be a JSON array")
    if len(value) > 300 and not drop_invalid:
        raise ValueError("questions must contain at most 300 items")
    result: list[dict] = []
    seen: set[tuple[str, str]] = set()
    for item in value:
        question = _valid_question(item)
        if question is None:
            if drop_invalid:
                continue
            raise ValueError("question file contains an invalid question")
        key = (question["lang"], unicodedata.normalize("NFKC", question["q"]).strip().casefold())
        if dedupe and key in seen:
            if drop_invalid:
                continue
            raise ValueError("question file contains duplicate prompts")
        seen.add(key)
        result.append(question)
        if len(result) == 300:
            break
    return result


def _read_question_file(path: Path) -> list[dict]:
    raw = path.read_bytes()
    if len(raw) > 64 * 1024:
        raise ValueError("question file exceeds 64 KiB")
    document = json.loads(raw.decode("utf-8"))
    questions = document.get("questions") if isinstance(document, dict) else document
    if isinstance(document, dict) and set(document) != {"questions"}:
        raise ValueError("question object must contain only 'questions'")
    validated = _validated_questions(questions, drop_invalid=False)
    if _question_wire_size(validated) > 64 * 1024:
        raise ValueError("question data exceeds the phone's 64 KiB limit")
    return validated


def _generation_prompt(count: int) -> str:
    return f"""Generate {count} English and {count} Simplified Chinese high-school general-knowledge questions.
Spread them across math, geography, physics, chemistry, biology, history, and language.
Use short unambiguous answers with several accepted forms where useful. Avoid trick questions,
time-sensitive facts, and arithmetic requiring a calculator. Return exactly one JSON object,
using this structure (lang must be "en" or "zh"):
{{"questions":[{{"lang":"en","q":"12 × 3 = ?","answers":["36","thirty-six"],"domain":"math"}},{{"lang":"zh","q":"水的化学式是什么？","answers":["H2O"],"domain":"chemistry"}}]}}
Each language must have {count} questions. Each q must have 1–200 characters;
provide 1–10 accepted answers of 1–60 characters each and a domain of at most
30 characters. Do not use control characters. Print only JSON, without fences or explanation.
"""


def _extract_generated_document(output: str) -> dict:
    decoder = json.JSONDecoder()
    for index, character in enumerate(output):
        if character != "{":
            continue
        try:
            value, _ = decoder.raw_decode(output, index)
        except json.JSONDecodeError:
            continue
        if isinstance(value, dict) and "questions" in value:
            return value
    raise QuestionGenerationError("command output did not contain a JSON question object")


def _generate_questions(options, count: int) -> list[dict]:
    argv = options.question_argv
    if not argv:
        raise ValueError("no [notify] question_command is configured; set it to an AI CLI command")
    try:
        completed = subprocess.run(
            argv, input=_generation_prompt(count), text=True, capture_output=True,
            timeout=options.question_timeout_s, check=False,
        )
    except subprocess.TimeoutExpired as exc:
        raise QuestionGenerationError("command timed out") from exc
    except OSError as exc:
        raise QuestionGenerationError(f"could not run command: {exc}") from exc
    if completed.returncode != 0:
        raise QuestionGenerationError(f"command exited with status {completed.returncode}")
    document = _extract_generated_document(completed.stdout)
    if set(document) != {"questions"}:
        raise QuestionGenerationError("generated JSON object must contain only 'questions'")
    try:
        questions = _validated_questions(document["questions"], drop_invalid=True, dedupe=True)
    except ValueError as exc:
        raise QuestionGenerationError(str(exc)) from exc
    counts = {lang: sum(question["lang"] == lang for question in questions) for lang in ("en", "zh")}
    if counts["en"] < 10 or counts["zh"] < 10:
        raise QuestionGenerationError("fewer than 10 valid questions remain for each language")
    if _question_wire_size(questions) > 64 * 1024:
        raise QuestionGenerationError("valid questions exceed the phone's 64 KiB limit")
    return questions


def _question_wire_size(questions: list[dict]) -> int:
    return len(json.dumps(
        {"questions": questions}, ensure_ascii=False, separators=(",", ":"),
    ).encode("utf-8"))


def _write_questions(path: Path, document: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(path.name + ".tmp")
    temporary.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)


def _print_summary(summary: dict) -> None:
    source = "your computer" if summary["source"] == "computer" else "built-in"
    print(f"Questions: {summary['count']} from {source} "
          f"(English: {summary['counts']['en']}, Chinese: {summary['counts']['zh']})")


def _print_local_summary(questions: list[dict], output: str | None) -> None:
    en = sum(question["lang"] == "en" for question in questions)
    zh = len(questions) - en
    destination = f" in {output}" if output else ""
    print(f"Generated {len(questions)} questions{destination} (English: {en}, Chinese: {zh})")
