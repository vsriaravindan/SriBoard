# SriBoard — Portfolio Showcase

> **This file is the source of truth for portfolio descriptions.** When updating
> the portfolio site's project entry for SriBoard, copy from here. The README
> is fork-upstream focused; this is your portfolio-pitch version.

## One-liner

Privacy-first Android keyboard (fork of HeliBoard / AOSP OpenBoard) with
**inline AI text correction and translation** triggered from a custom toolbar —
all AI calls use your own API keys, app has **no internet permission by
default**.

## What stands out (interview pitch)

1. **AI inside the keyboard, no Accessibility Service needed** — most "AI
   keyboard" apps use Accessibility Services (privacy nightmare). SriBoard
   integrates AI as a first-class `Keyboard` action, so it works inside any
   input field, in any app.
2. **Bring-your-own-key** — supports Gemini, Grok (xAI), DeepSeek (Flash +
   Pro), and any OpenAI-compatible endpoint. User owns their data.
3. **Privacy-first by manifest** — `android.permission.INTERNET` is **not
   declared** by default; AI features only enable it when the user opts in.
4. **Shipping production** — v2.3 with iterative feature releases (bulk
   dictionary import, Gboard mode, markdown-to-plain-text before commit, JSON
   escape decoding in AI responses).
5. **Mainstream keyboard UX** — clipboard history, glide typing support,
   one-handed mode, split keyboard, multilingual typing, 7 fonts, customizable
   themes.

## Tech

| Layer | Tech |
|---|---|
| Language | Kotlin 2.x |
| UI | Android Views (custom `KeyboardView`), XML layouts |
| Base | Fork of HeliBoard (AOSP / OpenBoard lineage) |
| AI | Gemini 2.0 Flash, Grok-2, DeepSeek Chat/Reasoner, OpenAI-compat |
| HTTP | OkHttp 4.x |
| Storage | SharedPreferences (settings), internal storage (user dictionaries) |
| Build | Gradle, custom keystore for release |

## Features shipped (v2.3)

- AI toolbar: Fix, Translate to Tamil, 5 user-defined custom presets
- 5 AI providers (Gemini, Grok, DeepSeek Flash, DeepSeek Pro, OpenAI-compat)
- Bulk dictionary import (paste `word<TAB>shortcut` lines)
- Gboard layout mode (identical phone+tablet key placement, number row on
  tabs)
- Model quick-pick dropdown (current models: gemini-2.0-flash, grok-2,
  deepseek-chat, deepseek-reasoner)
- Markdown-to-plain-text pre-commit
- Current model name badges

## Stats

- **v2.3** latest
- **0 declared permissions** by default (opt-in INTERNET for AI)
- **5** AI providers supported

## For the portfolio site

- **Slug:** `sriboard`
- **Role:** Android Developer (Fork Maintainer)
- **Description:** Privacy-first Android keyboard forked from HeliBoard, with
  inline AI text correction and translation via 5 AI providers (Gemini, Grok,
  DeepSeek, OpenAI-compat) — works inside any app without Accessibility
  Service.
- **Standout:** Inline AI inside the keyboard (no Accessibility Service), BYOK
  for 5 providers, manifest declares no INTERNET permission by default.
- **Highlights:**
  - 5 AI providers: Gemini, Grok, DeepSeek Flash/Pro, OpenAI-compatible
  - Inline AI in any input field — no Accessibility Service required
  - No INTERNET permission by default (opt-in only for AI features)
  - v2.3 iterative shipping: bulk import, Gboard mode, model quick-pick
  - Custom AI toolbar with 7 presets (Fix, Translate, 5 user-defined)
  - Privacy-first: user owns their API keys, data never persists on a server

## Repo

`https://github.com/vsriaravindan/SriBoard`