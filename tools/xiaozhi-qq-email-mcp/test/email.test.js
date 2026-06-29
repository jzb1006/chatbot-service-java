import test from "node:test";
import assert from "node:assert/strict";

import {
  ASSISTANT_SIGNATURE,
  buildEmailText,
  buildEmailHtml,
  buildUnreadEmailSummary,
  getEmailText,
  listUnreadEmails,
  loadRecipientConfig,
  normalizeEmailText,
  parseRecipientsConfig,
  sanitizeEmailHtml,
  handleRequest,
  validateSendEmailArgs,
} from "../src/index.js";

test("parseRecipientsConfig loads alias whitelist from JSON", () => {
  const recipients = parseRecipientsConfig('{"我自己":"me@qq.com","项目通知":"notify@example.com"}');

  assert.deepEqual(recipients, {
    "我自己": "me@qq.com",
    "项目通知": "notify@example.com",
  });
});

test("loadRecipientConfig rejects empty config", () => {
  assert.throws(() => loadRecipientConfig({}), /QQ_EMAIL_RECIPIENTS/);
});

test("validateSendEmailArgs only accepts configured recipient aliases", () => {
  const recipients = { "我自己": "me@qq.com" };

  assert.deepEqual(
    validateSendEmailArgs(
      {
        recipientAlias: " 我自己 ",
        subject: " 项目部署完成 ",
        text: " 页面已经上线。 ",
      },
      recipients,
    ),
    {
      recipientAlias: "我自己",
      to: "me@qq.com",
      subject: "项目部署完成",
      text: "页面已经上线。",
    },
  );
  assert.throws(
    () => validateSendEmailArgs({ recipientAlias: "陌生人", subject: "Hi", text: "Hello" }, recipients),
    /不在白名单/,
  );
});

test("buildEmailText appends AI assistant signature exactly once", () => {
  assert.equal(
    buildEmailText("你好，页面已经部署完成。"),
    `你好，页面已经部署完成。\n\n${ASSISTANT_SIGNATURE}`,
  );
  assert.equal(
    buildEmailText(`你好。\n\n${ASSISTANT_SIGNATURE}`),
    `你好。\n\n${ASSISTANT_SIGNATURE}`,
  );
});

test("sanitizeEmailHtml keeps only safe formatting tags and safe links", () => {
  assert.equal(
    sanitizeEmailHtml(
      '<p onclick="bad()">您好 <strong>项目</strong><script>alert(1)</script><a href="javascript:alert(1)">危险链接</a><a href="https://example.com?a=1&b=2">安全链接</a><img src=x></p>',
    ),
    '<p>您好 <strong>项目</strong><a>危险链接</a><a href="https://example.com?a=1&amp;b=2">安全链接</a></p>',
  );
});

test("sanitizeEmailHtml keeps safe inline css", () => {
  assert.equal(
    sanitizeEmailHtml(
      '<p style="font-size: 14px; color: #333; background-color: rgb(240, 240, 240); text-align: center; padding: 12px; border: 1px solid #ddd;">您好</p>',
    ),
    '<p style="font-size:14px;color:#333;background-color:rgb(240, 240, 240);text-align:center;padding:12px;border:1px solid #ddd">您好</p>',
  );
});

test("sanitizeEmailHtml keeps richer email layout tags, style blocks, classes, and https images", () => {
  assert.equal(
    sanitizeEmailHtml(
      '<style>.hero{background:linear-gradient(135deg,#0f766e,#f59e0b);color:#fff;padding:24px}.hero img{max-width:100%;border-radius:8px}</style><div class="hero" id="x"><h1>重庆七日游</h1><img src="https://example.com/chongqing.jpg" alt="重庆夜景" width="640" height="320" onclick="bad()"><table><tr><th>日期</th><th>安排</th></tr><tr><td>D1</td><td><span class="tag">解放碑</span></td></tr></table><a class="button" href="https://example.com?a=1&b=2">查看地图</a></div>',
    ),
    '<style>.hero{background:linear-gradient(135deg,#0f766e,#f59e0b);color:#fff;padding:24px}.hero img{max-width:100%;border-radius:8px}</style><div class="hero"><h1>重庆七日游</h1><img src="https://example.com/chongqing.jpg" alt="重庆夜景" width="640" height="320"><table><tr><th>日期</th><th>安排</th></tr><tr><td>D1</td><td><span class="tag">解放碑</span></td></tr></table><a href="https://example.com?a=1&amp;b=2" class="button">查看地图</a></div>',
  );
});

test("sanitizeEmailHtml removes unsafe inline css", () => {
  assert.equal(
    sanitizeEmailHtml(
      '<p style="position:absolute; display:none; background-image:url(https://example.com/a.png); color: expression(alert(1)); margin: 8px;">您好</p>',
    ),
    '<p style="margin:8px">您好</p>',
  );
});

test("sanitizeEmailHtml removes unsafe rich html resources and css", () => {
  assert.equal(
    sanitizeEmailHtml(
      '<style>@import url(https://evil.example/a.css);.x{background-image:url(javascript:alert(1));position:absolute;color:#333}.safe{margin:8px}</style><img src="javascript:alert(1)" alt="x"><img src="data:image/png;base64,aaa" alt="x"><img src="http://example.com/a.png" alt="A"><a href="javascript:alert(1)" class="bad">坏链接</a>',
    ),
    '<style>.x{color:#333}.safe{margin:8px}</style><img src="http://example.com/a.png" alt="A"><a class="bad">坏链接</a>',
  );
});

test("buildEmailHtml appends AI assistant signature once", () => {
  assert.equal(
    buildEmailHtml("<p>页面已经上线。</p>"),
    `<p>页面已经上线。</p><p>${ASSISTANT_SIGNATURE}</p>`,
  );
  assert.equal(
    buildEmailHtml(`<p>页面已经上线。</p><p>${ASSISTANT_SIGNATURE}</p>`),
    `<p>页面已经上线。</p><p>${ASSISTANT_SIGNATURE}</p>`,
  );
});

test("normalizeEmailText strips html and collapses whitespace for TTS summaries", () => {
  assert.equal(
    normalizeEmailText("<p>您好：</p><p>请查看&nbsp;<b>部署结果</b></p>", 40),
    "您好： 请查看 部署结果",
  );
});

test("buildUnreadEmailSummary returns latest five unread email summaries", () => {
  const messages = Array.from({ length: 7 }, (_, index) => ({
    uid: index + 1,
    envelope: {
      date: new Date(`2026-06-29T0${index}:00:00+08:00`),
      from: [{ name: `发件人${index}`, address: `sender${index}@example.com` }],
      subject: `主题${index}`,
    },
    body: `正文${index}。这是一封比较长的邮件，用于生成摘要。`,
  }));

  const summary = buildUnreadEmailSummary(messages);

  assert.equal(summary.emails.length, 5);
  assert.deepEqual(
    summary.emails.map((email) => email.uid),
    [7, 6, 5, 4, 3],
  );
  assert.equal(summary.tts_text, "有 5 封未读邮件。最新一封来自 发件人6，主题是 主题6。");
});

test("listUnreadEmails returns empty result without fetching when there are no unread emails", async () => {
  let fetchCalled = false;
  const client = {
    async connect() {},
    async getMailboxLock() {
      return { release() {} };
    },
    async search() {
      return [];
    },
    async *fetch() {
      fetchCalled = true;
      throw new Error("fetch should not be called");
    },
    async logout() {},
  };

  const summary = await listUnreadEmails({ client });

  assert.equal(fetchCalled, false);
  assert.deepEqual(summary, {
    count: 0,
    emails: [],
    tts_text: "没有未读邮件。",
  });
});

test("listUnreadEmails fetches unread messages by UID", async () => {
  const fetchCalls = [];
  const client = {
    async connect() {},
    async getMailboxLock() {
      return { release() {} };
    },
    async search() {
      return [42];
    },
    async *fetch(range, query, options) {
      fetchCalls.push({ range, query, options });
    },
    async logout() {},
  };

  await listUnreadEmails({ client });

  assert.equal(fetchCalls.length, 1);
  assert.deepEqual(fetchCalls[0].range, [42]);
  assert.deepEqual(fetchCalls[0].options, { uid: true });
});

test("getEmailText fetches the selected message by UID", async () => {
  const fetchCalls = [];
  const client = {
    async connect() {},
    async getMailboxLock() {
      return { release() {} };
    },
    async *fetch(range, query, options) {
      fetchCalls.push({ range, query, options });
    },
    async logout() {},
  };

  await assert.rejects(() => getEmailText({ uid: 88 }, { client }), /未找到 UID 88/);
  assert.equal(fetchCalls.length, 1);
  assert.deepEqual(fetchCalls[0].range, [88]);
  assert.deepEqual(fetchCalls[0].options, { uid: true });
});

test("tools/list exposes the restricted QQ email tool surface", async () => {
  const response = await handleRequest({ jsonrpc: "2.0", id: 1, method: "tools/list" });

  assert.deepEqual(
    response.result.tools.map((tool) => tool.name),
    ["list_recipients", "send_email", "send_html_email", "list_unread_emails", "get_email_text"],
  );
});

test("send_email tool rejects aliases outside whitelist before SMTP", async () => {
  const response = await handleRequest(
    {
      jsonrpc: "2.0",
      id: 2,
      method: "tools/call",
      params: {
        name: "send_email",
        arguments: {
          recipientAlias: "陌生人",
          subject: "项目部署完成",
          text: "页面已经上线。",
        },
      },
    },
    {
      env: {
        QQ_EMAIL_USER: "sender@qq.com",
        QQ_EMAIL_AUTH_CODE: "secret",
        QQ_EMAIL_RECIPIENTS: '{"我自己":"me@qq.com"}',
      },
      transporter: {
        async sendMail() {
          throw new Error("should not send");
        },
      },
    },
  );

  assert.equal(response.result.isError, true);
  assert.match(response.result.content[0].text, /不在白名单/);
});

test("send_email tool sends signed text to configured recipient alias", async () => {
  const sent = [];
  const response = await handleRequest(
    {
      jsonrpc: "2.0",
      id: 3,
      method: "tools/call",
      params: {
        name: "send_email",
        arguments: {
          recipientAlias: "我自己",
          subject: "项目部署完成",
          text: "页面已经上线。",
        },
      },
    },
    {
      env: {
        QQ_EMAIL_USER: "sender@qq.com",
        QQ_EMAIL_AUTH_CODE: "secret",
        QQ_EMAIL_RECIPIENTS: '{"我自己":"me@qq.com"}',
      },
      transporter: {
        async sendMail(options) {
          sent.push(options);
          return { messageId: "message-1" };
        },
      },
    },
  );

  assert.equal(response.result.isError, false);
  assert.deepEqual(sent, [
    {
      from: "sender@qq.com",
      to: "me@qq.com",
      subject: "项目部署完成",
      text: `页面已经上线。\n\n${ASSISTANT_SIGNATURE}`,
    },
  ]);
});

test("send_html_email tool rejects aliases outside whitelist before SMTP", async () => {
  const response = await handleRequest(
    {
      jsonrpc: "2.0",
      id: 4,
      method: "tools/call",
      params: {
        name: "send_html_email",
        arguments: {
          recipientAlias: "陌生人",
          subject: "HTML 测试",
          html: "<p>页面已经上线。</p>",
        },
      },
    },
    {
      env: {
        QQ_EMAIL_USER: "sender@qq.com",
        QQ_EMAIL_AUTH_CODE: "secret",
        QQ_EMAIL_RECIPIENTS: '{"项目通知":"notify@example.com"}',
      },
      transporter: {
        async sendMail() {
          throw new Error("should not send");
        },
      },
    },
  );

  assert.equal(response.result.isError, true);
  assert.match(response.result.content[0].text, /不在白名单/);
});

test("send_html_email tool sends sanitized html and plain text fallback", async () => {
  const sent = [];
  const response = await handleRequest(
    {
      jsonrpc: "2.0",
      id: 5,
      method: "tools/call",
      params: {
        name: "send_html_email",
        arguments: {
          recipientAlias: "项目通知",
          subject: "HTML 测试",
          html: '<p onclick="bad()" style="font-size: 14px; color: #333; position:absolute;">页面 <strong>已经上线</strong>。</p><script>alert(1)</script>',
        },
      },
    },
    {
      env: {
        QQ_EMAIL_USER: "sender@qq.com",
        QQ_EMAIL_AUTH_CODE: "secret",
        QQ_EMAIL_RECIPIENTS: '{"项目通知":"notify@example.com"}',
      },
      transporter: {
        async sendMail(options) {
          sent.push(options);
          return { messageId: "message-html-1" };
        },
      },
    },
  );

  assert.equal(response.result.isError, false);
  assert.deepEqual(sent, [
    {
      from: "sender@qq.com",
      to: "notify@example.com",
      subject: "HTML 测试",
      text: `页面 已经上线 。\n\n${ASSISTANT_SIGNATURE}`,
      html: `<p style="font-size:14px;color:#333">页面 <strong>已经上线</strong>。</p><p>${ASSISTANT_SIGNATURE}</p>`,
    },
  ]);
});
