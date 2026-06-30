# xiaozhi-qq-email-mcp

QQ 邮箱 MCP server for Hermes voice assistant.

## Scope

This MCP is intentionally narrow:

- Send email through one QQ mailbox.
- Send restricted HTML email through one QQ mailbox.
- Only send to configured recipient aliases.
- List at most 5 latest unread inbox emails.
- Read a selected email as sanitized plain text.
- Always append the fixed AI assistant signature.

It does not support arbitrary recipient addresses, recipient mutation tools, attachments, cc, bcc, forms, scripts, external CSS files, or mailbox runtime reconfiguration.

## Tools

- `list_recipients`
- `send_email`
- `send_html_email`
- `list_unread_emails`
- `get_email_text`

## Environment

```bash
export QQ_EMAIL_USER="<sender@qq.com>"
export QQ_EMAIL_AUTH_CODE="<qq-mail-auth-code>"
export QQ_EMAIL_RECIPIENTS='{"我自己":"<receiver@qq.com>","项目通知":"<notify@example.com>"}'
```

Optional defaults:

```bash
export QQ_EMAIL_SMTP_HOST="smtp.qq.com"
export QQ_EMAIL_SMTP_PORT="587"
export QQ_EMAIL_SMTP_SECURE="false"
export QQ_EMAIL_IMAP_HOST="imap.qq.com"
export QQ_EMAIL_IMAP_PORT="993"
export QQ_EMAIL_IMAP_SECURE="true"
export QQ_EMAIL_TIMEOUT_MS="30000"
```

## Hermes Behavior Rules

Hermes should handle natural language understanding:

- Infer `recipientAlias` from user speech.
- Parse scheduled send time when needed.
- Choose plain text vs HTML automatically. Use plain text for short personal messages; use HTML for structured project notices, deployment summaries, links, highlighted status, travel plans, reports, or content that benefits from visual hierarchy.
- For HTML email, prefer a polished visual layout with a headline section, clear sections, highlighted facts, buttons or links when useful, tables for dense comparisons, and http/https images when they materially help the reader.
- Generate Gmail/QQ-compatible email HTML by default: use table-based body layout, keep critical styles inline, and treat `<style>` blocks/classes as progressive enhancement instead of required layout.
- Polish the subject and body before sending.
- Summarize recipient, subject, and body before asking the user for confirmation.
- For scheduled email, create the Hermes cron task only after confirmation.

The MCP enforces deterministic constraints:

- `recipientAlias` must exist in `QQ_EMAIL_RECIPIENTS`.
- Subject and body must be non-empty.
- Subject max length is 120 characters.
- Body max length is 5000 characters.
- HTML body max length is 10000 characters.
- HTML sending keeps common email tags: `div`, `span`, `h1`-`h3`, `p`, `br`, `hr`, `strong`, `b`, `em`, `i`, `ul`, `ol`, `li`, `a`, `table`, `thead`, `tbody`, `tfoot`, `tr`, `th`, `td`, `img`, and `style`.
- HTML sending supports classes, restricted inline CSS, and restricted `<style>` blocks.
- For cross-client rendering, critical layout and visual styles should be inline. `<style>` blocks are allowed but must not be the only source of layout, spacing, button, or image sizing styles.
- CSS only keeps safe properties such as `color`, `background`, `background-color`, `font-size`, `font-weight`, `font-style`, `font-family`, `text-align`, `line-height`, `padding`, `margin`, `border`, `border-radius`, `width`, `max-width`, `height`, `display`, `vertical-align`, and table spacing properties.
- CSS drops `url()`, `expression()`, `javascript:`, `data:`, `@import`, `position`, `display:none`, and unsupported properties.
- Images must use `http://` or `https://` URLs. The sanitizer keeps only safe `src`, `alt`, `width`, `height`, `class`, and `style` attributes.
- HTML sending generates a plain text fallback.
- The signature `本邮件由志彬的 AI 助手代为发送。` is always appended.

## Example MCP Configuration

```json
{
  "mcpServers": {
    "xiaozhi-qq-email": {
      "command": "node",
      "args": ["/opt/data/mcp-servers/xiaozhi-qq-email-mcp/src/index.js"],
      "env": {
        "QQ_EMAIL_USER": "<sender@qq.com>",
        "QQ_EMAIL_AUTH_CODE": "<qq-mail-auth-code>",
        "QQ_EMAIL_RECIPIENTS": "{\"我自己\":\"<receiver@qq.com>\"}"
      }
    }
  }
}
```

## Development

```bash
npm install
npm test
npm audit --omit=dev
```
