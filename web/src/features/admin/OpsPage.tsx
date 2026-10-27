import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { appendAuditEvent } from "@/lib/api/admin";
import {
  AdminSection,
  DistRow,
  EmptyRow,
  ReasonDialog,
  Row,
  RowAct,
  RowList,
  RowText,
  StatePill,
  useToastMessage,
} from "./ui";

/** 「填原因」弹窗的一次请求。 */
interface ReasonRequest {
  title: string;
  description: string;
  options: string[];
  onConfirm: (reason: string) => void;
}

/** 会话演示数据；吊销只更新本页状态并写演示审计。 */
interface SessionDemo {
  id: string;
  device: string;
  ip: string;
  last: string;
  current: boolean;
}

const INITIAL_SESSIONS: SessionDemo[] = [
  { id: "sess1", device: "MacBook · Chrome", ip: "192.168.1.5", last: "今天 14:20", current: true },
  { id: "sess2", device: "iPhone · Safari", ip: "192.168.1.8", last: "昨天 21:40", current: false },
  { id: "sess3", device: "Windows · Edge", ip: "10.0.0.23", last: "3 天前 09:12", current: false },
];

/** 备份演示数据。 */
const BACKUPS = [
  { at: "今天 02:00", size: "18.4 MB", status: "成功" },
  { at: "昨天 02:00", size: "18.1 MB", status: "成功" },
  { at: "10-21 02:00", size: "17.9 MB", status: "成功" },
];

/** 限流规则演示数据。 */
const RATE_RULES: Array<{ label: string; note: string }> = [
  { label: "登录", note: "IP 5 / 分钟 · 账号 5 / 15 分钟" },
  { label: "邀请码", note: "IP 3 / 分钟 · 码 10 / 小时" },
  { label: "深度批改", note: "用户 10 / 分钟 · 全局并发 2" },
];

/** 判分版本演示数据；与「改判写新记录」的 append-only 约定配套。 */
const GRADING_VERSIONS = [
  { key: "normalizer", version: "v2", note: "全半角 + 弯引号 + 去标点" },
  { key: "rubric", version: "v3", note: "五维 100 分" },
  { key: "prompt", version: "v5", note: "整句翻译批改" },
  { key: "engine", version: "v1", note: "本地规则 + 模型" },
];

/**
 * 运维页签：会话吊销、限流规则、备份恢复演练与判分版本重放。
 * 本页为演示态：数据为固定示例，不读写服务端；吊销与演练会写演示审计并以 toast 反馈。
 */
export function OpsPage() {
  const { show: toast, node: toastNode } = useToastMessage();
  const [sessions, setSessions] = useState(INITIAL_SESSIONS);
  const [drillMessage, setDrillMessage] = useState("");
  const [reasonRequest, setReasonRequest] = useState<ReasonRequest | null>(null);

  // 演示审计：真实后端没有该端点时静默忽略，不打扰演示。
  const auditMutation = useMutation({ mutationFn: appendAuditEvent });

  return (
    <div>
      <p className="font-mono text-[11px] tracking-[0.06em] text-ink-faint">
        本页为演示态：会话、备份与判分版本为固定示例数据，不读写服务端；吊销会话会写入演示审计。
      </p>

      <AdminSection title="会话">
        <RowList>
          {sessions.length === 0 ? (
            <EmptyRow>没有活跃会话。</EmptyRow>
          ) : (
            sessions.map((session) => (
              <Row key={session.id}>
                <RowText
                  main={`${session.device}${session.current ? "（当前）" : ""}`}
                  meta={`${session.ip} · ${session.last}`}
                />
                <StatePill tone={session.current ? "ok" : "muted"}>
                  {session.current ? "在线" : "活跃"}
                </StatePill>
                {session.current ? (
                  <span aria-hidden="true" />
                ) : (
                  <RowAct
                    onClick={() =>
                      setReasonRequest({
                        title: "吊销会话",
                        description: `将吊销 ${session.device} 的会话，旧 Cookie 立即 401，用户需重新登录。`,
                        options: ["设备丢失", "异常登录", "其他"],
                        onConfirm: (reason) => {
                          setSessions((previous) =>
                            previous.filter((item) => item.id !== session.id),
                          );
                          auditMutation.mutate({
                            action: "SESSION_REVOKE",
                            targetType: "SESSION",
                            targetId: session.id,
                            reason,
                          });
                          toast("会话已吊销（演示）");
                        },
                      })
                    }
                  >
                    吊销
                  </RowAct>
                )}
              </Row>
            ))
          )}
        </RowList>
        <p className="mt-3 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
          改密或停用会吊销该用户全部会话，旧 Cookie 立即 401。
        </p>
      </AdminSection>

      <AdminSection title="限流">
        <div className="space-y-4">
          {RATE_RULES.map((rule) => (
            <DistRow key={rule.label} label={rule.label} note={rule.note} />
          ))}
        </div>
      </AdminSection>

      <AdminSection title="备份与恢复">
        <RowList>
          {BACKUPS.map((backup) => (
            <Row key={backup.at}>
              <RowText main={backup.at} meta={`${backup.size} · mysqldump --single-transaction`} />
              <StatePill tone="ok">{backup.status}</StatePill>
              <span aria-hidden="true" />
            </Row>
          ))}
        </RowList>
        {drillMessage ? (
          <p
            role="status"
            className="mt-3 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-moss"
          >
            {drillMessage}
          </p>
        ) : null}
        <div className="mt-9 flex gap-3.5">
          <button
            type="button"
            className="btn ghost"
            onClick={() => {
              setDrillMessage("演练完成：隔离恢复 1h42m，核心表校验和一致，登录与判分均通过。");
              toast("恢复演练通过（演示）");
            }}
          >
            跑一次恢复演练
          </button>
        </div>
      </AdminSection>

      <AdminSection title="判分版本">
        <RowList>
          {GRADING_VERSIONS.map((item) => (
            <Row key={item.key}>
              <RowText main={`${item.key} ${item.version}`} meta={item.note} />
              <span aria-hidden="true" />
              <span aria-hidden="true" />
            </Row>
          ))}
        </RowList>
        <p className="mt-3 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
          每条答题记录都绑定这些版本；改判写新记录，不覆盖旧分。
        </p>
        <div className="mt-9 flex gap-3.5">
          <button
            type="button"
            className="btn ghost"
            onClick={() => toast("重放 100 条完成，分数漂移 1.2%（演示）")}
          >
            重放最近 100 条
          </button>
        </div>
      </AdminSection>

      <ReasonDialog
        open={reasonRequest !== null}
        title={reasonRequest?.title ?? ""}
        description={reasonRequest?.description ?? ""}
        options={reasonRequest?.options ?? ["其他"]}
        onCancel={() => setReasonRequest(null)}
        onConfirm={(reason) => {
          const request = reasonRequest;
          setReasonRequest(null);
          request?.onConfirm(reason);
        }}
      />

      {toastNode}
    </div>
  );
}
