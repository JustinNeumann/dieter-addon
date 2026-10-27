// Claude quota for a CLAUDE_CODE_OAUTH_TOKEN login (`claude setup-token`): Dieter's probe
// quota-claude.mjs refuses those as "unsupported", so call the usage API ourselves and reuse its
// normalizeUsage. Usage: node quota_claude_token.mjs /path/to/runtime/quota-claude.mjs
import { pathToFileURL } from "node:url";
const { normalizeUsage } = await import(pathToFileURL(process.argv[2]).href);
const token = process.env.CLAUDE_CODE_OAUTH_TOKEN;
if (!token) throw new Error("CLAUDE_CODE_OAUTH_TOKEN is not set");
const response = await fetch("https://api.anthropic.com/api/oauth/usage", {
  headers: { authorization: `Bearer ${token}`, "anthropic-beta": "oauth-2025-04-20", "content-type": "application/json", "user-agent": "dieter-quota/1" },
  signal: AbortSignal.timeout(8_000),
});
if (!response.ok) throw new Error(`Claude usage request failed: HTTP ${response.status}`);
process.stdout.write(`${JSON.stringify(normalizeUsage({}, await response.json()))}\n`);
