# AI Features in Sriboard

Sriboard includes AI-powered text correction and translation features that work **entirely inside the keyboard** — no Accessibility Service, no Developer Options needed.

## How It Works

1. You enable AI Features in Settings → AI
2. Configure your API key (Google AI Gemini, Grok, DeepSeek, or any OpenAI-compatible provider)
3. AI toolbar keys appear in your keyboard toolbar (configurable in Settings → Toolbar)
4. Type text, tap an AI toolbar button → text is processed and replaced inline

## Supported Providers

| Provider | API Key Format | Default Model |
|---|---|---|
| Google AI (Gemini) | `AIza...` | gemini-2.0-flash |
| Grok (xAI) | `xai-...` | grok-2 |
| DeepSeek Flash | `sk-...` | deepseek-chat |
| DeepSeek Pro | `sk-...` | deepseek-reasoner |
| OpenAI Compatible | `sk-...` | gpt-4o-mini |

## Presets

| Preset | What It Does |
|---|---|
| **Fix** | Corrects English grammar/spelling errors |
| **Translate to Tamil** | Translates text to Tamil |
| **Custom 1-5** | User-defined prompts |

## Security

- **API keys are stored only** in Android's device-protected storage (credential-encrypted SharedPreferences)
- **Keys are sent ONLY** to the configured API endpoint in the HTTPS request
- **No telemetry**, no analytics, no data collection
- **No internet permission** in the manifest — network calls happen only when you trigger an AI toolbar key
- **Undo support** — tap the AI key again to restore your original text

## Backup Compatibility

Sriboard's backup system is identical to HeliBoard's. AI settings (prefixed with `ai_`) are automatically included in backups. A HeliBoard backup can be restored into Sriboard (AI settings simply default), and vice versa.
