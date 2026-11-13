"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { Badge, Button, Card, CardSkeleton, Input, Toggle } from "@/shared/components";
import { useNotificationStore } from "@/store/notificationStore";

const EMPTY_FORM = {
  enabled: false,
  botToken: "",
  webhookSecret: "",
  allowedChatIds: "",
  defaultModel: "",
  systemPrompt: "",
  historyLimit: 12,
  notifyQuota: true,
  notifyErrors: true,
  notifyCombos: true,
  quotaThreshold: 20,
};

const COMMANDS = [
  ["/help", "Show the command list"],
  ["/combo", "List combos · /combo <name> switches the bot"],
  ["/model", "Show or set the model (/model provider/model)"],
  ["/vision", "Show or edit the Vision Adapter pool"],
  ["/quota", "Usage + remaining quota per provider"],
  ["/clear", "Clear this chat's history"],
];

function parseChatIds(value) {
  return String(value || "")
    .split(/[\s,;]+/)
    .map((v) => v.trim())
    .filter(Boolean)
    .filter((v, i, all) => all.indexOf(v) === i);
}

function toForm(telegram = {}) {
  return {
    enabled: telegram.enabled === true,
    botToken: telegram.botToken || "",
    webhookSecret: telegram.webhookSecret || "",
    allowedChatIds: Array.isArray(telegram.allowedChatIds)
      ? telegram.allowedChatIds.join("\n")
      : String(telegram.allowedChatIds || ""),
    defaultModel: telegram.defaultModel || "",
    systemPrompt: telegram.systemPrompt || "",
    historyLimit: Number.isFinite(Number(telegram.historyLimit)) ? Number(telegram.historyLimit) : 12,
    notifyQuota: telegram.notifyQuota !== false,
    notifyErrors: telegram.notifyErrors !== false,
    notifyCombos: telegram.notifyCombos !== false,
    quotaThreshold: Number.isFinite(Number(telegram.quotaThreshold)) ? Number(telegram.quotaThreshold) : 20,
  };
}

function buildPayload(form) {
  return {
    enabled: !!form.enabled,
    botToken: String(form.botToken || "").trim(),
    // Round-tripped unchanged: updateSettings replaces the whole telegram object,
    // so dropping the secret here would disable webhook authentication.
    webhookSecret: String(form.webhookSecret || "").trim(),
    allowedChatIds: parseChatIds(form.allowedChatIds),
    defaultModel: String(form.defaultModel || "").trim(),
    systemPrompt: String(form.systemPrompt || ""),
    historyLimit: Math.min(50, Math.max(0, Number(form.historyLimit) || 0)),
    notifyQuota: !!form.notifyQuota,
    notifyErrors: !!form.notifyErrors,
    notifyCombos: !!form.notifyCombos,
    quotaThreshold: Math.min(100, Math.max(1, Number(form.quotaThreshold) || 20)),
  };
}

function Field({ label, hint, children }) {
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-sm font-medium text-text-main">{label}</label>
      {children}
      {hint && <p className="text-xs text-text-muted">{hint}</p>}
    </div>
  );
}

function TextArea({ value, onChange, placeholder, rows = 3, className = "" }) {
  return (
    <textarea
      value={value}
      onChange={onChange}
      placeholder={placeholder}
      rows={rows}
      className={`w-full py-2.5 px-3 text-sm text-text-main bg-surface-2 rounded-[10px] border border-transparent placeholder-text-muted/70 focus:outline-none focus:ring-2 focus:ring-brand-500/30 focus:border-brand-500/40 resize-y ${className}`}
    />
  );
}

export default function TelegramPage() {
  const notifySuccess = useNotificationStore((s) => s.success);
  const notifyError = useNotificationStore((s) => s.error);
  const [form, setForm] = useState(EMPTY_FORM);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [action, setAction] = useState("");
  const [status, setStatus] = useState(null);
  const [origin, setOrigin] = useState("");

  const webhookUrl = origin ? `${origin}/api/telegram/webhook` : "/api/telegram/webhook";

  useEffect(() => {
    setOrigin(window.location.origin);
  }, []);

  const load = useCallback(async () => {
    try {
      const res = await fetch("/api/settings", { cache: "no-store" });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error || "Failed to load settings");
      setForm(toForm(data.telegram));
    } catch (error) {
      notifyError(error.message || "Failed to load settings");
    } finally {
      setLoading(false);
    }
  }, [notifyError]);

  useEffect(() => {
    load();
  }, [load]);

  const patch = (next) => setForm((prev) => ({ ...prev, ...next }));

  const saveConfig = useCallback(
    async ({ silent = false } = {}) => {
      setSaving(true);
      try {
        const res = await fetch("/api/settings", {
          method: "PATCH",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ telegram: buildPayload(form) }),
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.error || "Failed to save");
        setForm(toForm(data.telegram));
        if (!silent) notifySuccess("Telegram settings saved");
        return true;
      } catch (error) {
        notifyError(error.message || "Failed to save settings");
        return false;
      } finally {
        setSaving(false);
      }
    },
    [form, notifyError, notifySuccess]
  );

  const runAction = useCallback(
    async (name) => {
      setAction(name);
      try {
        if (name === "test") {
          const res = await fetch("/api/telegram/test", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({}),
          });
          const data = await res.json();
          if (!res.ok || data.ok === false) throw new Error(data.error || "Test failed");
          notifySuccess("Test message sent");
          return;
        }

        if (name === "connect") {
          const saved = await saveConfig({ silent: true });
          if (!saved) return;
        }

        const res = await fetch("/api/telegram/setup", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ action: name }),
        });
        const data = await res.json();
        if (!res.ok || data.ok === false) throw new Error(data.error || "Request failed");

        if (name === "status" || name === "connect") setStatus(data);
        if (name === "connect") {
          notifySuccess("Webhook connected");
          await load();
        }
        if (name === "disconnect") {
          setStatus(null);
          notifySuccess("Webhook disconnected");
          await load();
        }
      } catch (error) {
        notifyError(error.message || "Telegram request failed");
      } finally {
        setAction("");
      }
    },
    [load, notifyError, notifySuccess, saveConfig]
  );

  const configured = !!form.botToken.trim();
  const webhookActive = !!status?.webhook?.url;

  const botLabel = useMemo(() => {
    const username = status?.bot?.username;
    return username ? `@${username}` : status?.bot?.first_name || "";
  }, [status]);

  if (loading) {
    return (
      <div className="p-4 sm:p-6 max-w-5xl mx-auto">
        <CardSkeleton />
      </div>
    );
  }

  return (
    <div className="p-4 sm:p-6 max-w-5xl mx-auto flex flex-col gap-5">
      <div>
        <h1 className="text-2xl font-bold text-text-main">Telegram Bot</h1>
        <p className="text-sm text-text-muted mt-1">
          Chat through 9Router from Telegram, run Combo / Vision Adapter / Quota commands, and receive
          proactive alerts. The bot is an internal client of this same proxy.
        </p>
      </div>

      <Card
        title="Bot"
        subtitle="Create a bot with @BotFather, paste its token, then connect the webhook."
        icon="smart_toy"
        action={
          <Badge variant={form.enabled && configured ? "success" : "default"}>
            {form.enabled && configured ? "Enabled" : "Disabled"}
          </Badge>
        }
      >
        <div className="flex flex-col gap-4">
          <Input
            label="Bot token"
            type="password"
            placeholder="123456789:AA…"
            value={form.botToken}
            onChange={(e) => patch({ botToken: e.target.value })}
            hint="Stored in dashboard settings. Never shared outside the Telegram API + this proxy."
          />

          <Toggle
            checked={form.enabled}
            onChange={(value) => patch({ enabled: value })}
            label="Enable the bot"
            description="Required for the webhook to accept updates and for notifications."
          />

          <Field
            label="Allowed chat IDs"
            hint="One per line. Message the bot first — with an empty list it replies with your chat ID so you can paste it here."
          >
            <TextArea
              value={form.allowedChatIds}
              onChange={(e) => patch({ allowedChatIds: e.target.value })}
              placeholder={"123456789\n-1001234567890"}
              rows={3}
            />
          </Field>

          <div className="flex flex-wrap gap-2">
            <Button onClick={() => saveConfig()} loading={saving} icon="save">
              Save
            </Button>
            <Button
              variant="secondary"
              onClick={() => runAction("connect")}
              loading={action === "connect"}
              disabled={!configured}
              icon="link"
            >
              Connect webhook
            </Button>
            <Button
              variant="secondary"
              onClick={() => runAction("status")}
              loading={action === "status"}
              disabled={!configured}
              icon="monitor_heart"
            >
              Check status
            </Button>
            <Button
              variant="secondary"
              onClick={() => runAction("test")}
              loading={action === "test"}
              disabled={!configured}
              icon="send"
            >
              Send test
            </Button>
            <Button
              variant="outline"
              onClick={() => runAction("disconnect")}
              loading={action === "disconnect"}
              disabled={!configured}
              icon="link_off"
            >
              Disconnect
            </Button>
          </div>

          <div className="rounded-[10px] bg-surface-2 p-3 text-xs text-text-muted break-all">
            <div>
              <span className="font-medium text-text-main">Webhook URL:</span> {webhookUrl}
            </div>
            {status ? (
              <div className="mt-2 flex flex-col gap-1">
                {botLabel && (
                  <div>
                    <span className="font-medium text-text-main">Bot:</span> {botLabel}
                  </div>
                )}
                <div>
                  <span className="font-medium text-text-main">Webhook:</span>{" "}
                  {status.webhook?.url || "not set"}
                </div>
                {typeof status.webhook?.pending_update_count === "number" && (
                  <div>
                    <span className="font-medium text-text-main">Pending updates:</span>{" "}
                    {status.webhook.pending_update_count}
                  </div>
                )}
                {status.webhook?.last_error_message && (
                  <div className="text-red-500">
                    <span className="font-medium">Last error:</span> {status.webhook.last_error_message}
                  </div>
                )}
              </div>
            ) : (
              <div className="mt-2">
                {webhookActive ? "Webhook connected." : "Connect the webhook to start receiving messages."}
              </div>
            )}
          </div>
        </div>
      </Card>

      <Card title="Chat behaviour" subtitle="Model routing, prompt, and history for incoming messages." icon="chat">
        <div className="flex flex-col gap-4">
          <Field
            label="Default model"
            hint="A combo name or provider/model. Leave empty to use your first combo."
          >
            <Input
              placeholder="my-combo or kr/claude-sonnet-4.5"
              value={form.defaultModel}
              onChange={(e) => patch({ defaultModel: e.target.value })}
            />
          </Field>

          <Field label="System prompt" hint="Prepended to every conversation started in Telegram.">
            <TextArea
              value={form.systemPrompt}
              onChange={(e) => patch({ systemPrompt: e.target.value })}
              placeholder="You are a concise assistant…"
              rows={4}
            />
          </Field>

          <Field label="History messages" hint="How many previous messages are replayed (0-50).">
            <Input
              type="number"
              min={0}
              max={50}
              value={form.historyLimit}
              onChange={(e) => patch({ historyLimit: e.target.value })}
            />
          </Field>

          <p className="text-xs text-text-muted">
            Send an image and the Vision Adapter picks a vision-capable model automatically. If the
            provider rejects the request, Combo fallback continues to the next member.
          </p>
        </div>
      </Card>

      <Card title="Notifications" subtitle="Proactive messages pushed to your allowed chats." icon="notifications">
        <div className="flex flex-col gap-4">
          <Toggle
            checked={form.notifyQuota}
            onChange={(value) => patch({ notifyQuota: value })}
            label="Quota alerts"
            description="When a tracked connection drops to or below the threshold."
          />
          <Field label="Quota threshold (%)" hint="Alert when remaining quota is at or below this value.">
            <Input
              type="number"
              min={1}
              max={100}
              value={form.quotaThreshold}
              onChange={(e) => patch({ quotaThreshold: e.target.value })}
            />
          </Field>
          <Toggle
            checked={form.notifyErrors}
            onChange={(value) => patch({ notifyErrors: value })}
            label="Connection errors"
            description="When a provider connection test fails."
          />
          <Toggle
            checked={form.notifyCombos}
            onChange={(value) => patch({ notifyCombos: value })}
            label="Combo fallback"
            description="When a combo member fails and the request moves to the next model."
          />
          <p className="text-xs text-text-muted">
            A daily cron job re-checks quotas on Vercel Hobby. Alerts are de-duplicated with cooldowns
            (quota/error: 6h, combo: 10m).
          </p>
        </div>
      </Card>

      <Card title="Commands" subtitle="Send these in the authorized chat." icon="terminal">
        <div className="flex flex-col gap-2 text-sm">
          {COMMANDS.map(([cmd, desc]) => (
            <div key={cmd} className="flex flex-col sm:flex-row sm:items-baseline gap-1 sm:gap-3">
              <code className="text-brand-500 font-mono text-xs sm:w-48 shrink-0">{cmd}</code>
              <span className="text-text-muted">{desc}</span>
            </div>
          ))}
        </div>
      </Card>
    </div>
  );
}