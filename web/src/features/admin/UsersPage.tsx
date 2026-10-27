import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ErrorState, LoadingBlock } from "@/components/feedback";
import { Pagination } from "@/components/pagination";
import {
  type AdminUser,
  appendAuditEvent,
  createInvite,
  deleteAdminUser,
  getAdminUsers,
  getAuditLogs,
  getInvites,
  getRawAttempt,
  getUserAttempts,
  updateUserRole,
  updateUserStatus,
} from "@/lib/api/admin";
import { meQueryOptions, queryKeys } from "@/lib/query";
import { actionLabel, relativeDayLabel } from "./constants";
import {
  AdminSearch,
  AdminSelect,
  Drawer,
  DrawerField,
  EmptyRow,
  HeatGrid,
  LiveMark,
  MiniMetrics,
  Modal,
  ReasonDialog,
  Row,
  RowList,
  useToastMessage,
} from "./ui";

/** 「填原因」弹窗的一次请求；onConfirm 由触发处闭包给定。 */
interface ReasonRequest {
  title: string;
  description: string;
  options: string[];
  onConfirm: (reason: string) => void;
}

/** 查看作答弹窗的数据。 */
interface RawAnswerView {
  zh: string;
  ref: string | null;
  answerText: string;
  score: number | null;
}

/**
 * 把 mock 原文响应里的 answer 还原成可读译文；语块拼空、整句取 text、看过答案据实说明。
 * @param answer 原始 answer 字段
 * @returns 可直接展示的文本
 */
function answerToText(answer: unknown): string {
  if (typeof answer !== "object" || answer === null) {
    return "—";
  }
  const record = answer as Record<string, unknown>;
  if (record.revealed === true) {
    return "（用户直接看了参考答案，未作答）";
  }
  if (typeof record.text === "string" && record.text !== "") {
    return record.text;
  }
  if (Array.isArray(record.slots)) {
    const values = record.slots
      .map((slot) =>
        typeof slot === "object" &&
        slot !== null &&
        typeof (slot as Record<string, unknown>).value === "string"
          ? ((slot as Record<string, unknown>).value as string)
          : "",
      )
      .filter((value) => value !== "");
    return values.length > 0 ? values.join(" ") : "—";
  }
  return "—";
}

/** 角色徽标（原型 .badge）：9px mono 边框片，管理员朱红。 */
function RoleBadge({ admin }: { admin: boolean }) {
  return (
    <span
      className={
        admin
          ? "inline-block whitespace-nowrap rounded-[2px] border border-vermilion/40 px-[6px] py-[3px] text-center font-mono text-[9px] tracking-[0.12em] text-vermilion"
          : "inline-block whitespace-nowrap rounded-[2px] border border-rule px-[6px] py-[3px] text-center font-mono text-[9px] tracking-[0.12em] text-ink-faint"
      }
    >
      {admin ? "管理员" : "用户"}
    </span>
  );
}

/**
 * 用户页签：邀请码区（原型 .invite）、搜索与筛选、用户行与用户抽屉。
 * 停用、删除、角色变更、查看原文、重置密码、导出都通过「填原因」弹窗确认并写审计。
 */
export function UsersPage() {
  const toast = useToastMessage();
  const queryClient = useQueryClient();
  const [keyword, setKeyword] = useState("");
  const [role, setRole] = useState<"all" | "USER" | "ADMIN">("all");
  const [status, setStatus] = useState<"all" | "active" | "disabled" | "deleted">("all");
  const [page, setPage] = useState(1);
  const [size, setSize] = useState<number>(10);
  const [drawerUserId, setDrawerUserId] = useState<string | null>(null);
  const [reasonRequest, setReasonRequest] = useState<ReasonRequest | null>(null);
  const [rawAnswer, setRawAnswer] = useState<RawAnswerView | null>(null);

  const usersQuery = useQuery({
    queryKey: queryKeys.admin.users({ page, size, keyword, role, status }),
    queryFn: ({ signal }) =>
      getAdminUsers(
        {
          page,
          size,
          ...(keyword.trim() === "" ? {} : { keyword: keyword.trim() }),
          ...(role === "all" ? {} : { role }),
          ...(status === "all" ? {} : { status }),
        },
        signal,
      ),
  });
  const invitesQuery = useQuery({
    queryKey: ["admin", "invites"],
    queryFn: ({ signal }) => getInvites(signal),
  });

  const createInviteMutation = useMutation({
    mutationFn: createInvite,
    onSuccess: (invite) => {
      void queryClient.invalidateQueries({ queryKey: ["admin", "invites"] });
      void queryClient.invalidateQueries({ queryKey: queryKeys.admin.auditLogs({}) });
      toast.show(`已生成邀请码 ${invite.code}`);
    },
    onError: (error) => toast.show(error instanceof Error ? error.message : "生成失败"),
  });

  /** 统一失效用户相关缓存；抽屉与列表共用。 */
  function invalidateUsers() {
    void queryClient.invalidateQueries({ queryKey: ["admin", "users"] });
    void queryClient.invalidateQueries({ queryKey: ["admin", "overview"] });
    void queryClient.invalidateQueries({ queryKey: queryKeys.admin.auditLogs({}) });
  }

  const drawerUser = usersQuery.data?.items.find((user) => user.id === drawerUserId) ?? null;

  return (
    <div>
      {/* 原型 .invite：标题 + 说明居左，码片靠右，生成按钮在末尾，底部分隔线。 */}
      <div className="-mt-2 flex flex-wrap items-center gap-5 border-b border-rule pb-[22px] pt-[18px]">
        <div>
          <p className="text-[13px] text-ink">邀请码</p>
          <p className="mt-2 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
            内测期间凭码注册，一码一人。
          </p>
        </div>
        <div className="ml-auto flex flex-wrap gap-2.5">
          {(invitesQuery.data ?? []).map((invite) => (
            <span
              key={invite.code}
              className={
                invite.used
                  ? "rounded-[2px] border border-rule px-[9px] py-[5px] font-mono text-[11px] tracking-[0.08em] text-ink-faint line-through opacity-60"
                  : "rounded-[2px] border border-rule px-[9px] py-[5px] font-mono text-[11px] tracking-[0.08em] text-ink-muted"
              }
            >
              {invite.code}
            </span>
          ))}
        </div>
        <button
          type="button"
          className="btn ghost"
          disabled={createInviteMutation.isPending}
          onClick={() => createInviteMutation.mutate()}
        >
          生成
        </button>
      </div>

      <div className="mt-3.5 mb-2.5 flex flex-wrap items-center gap-3.5">
        <AdminSearch
          aria-label="搜索用户名或邮箱"
          placeholder="搜索用户名或邮箱"
          value={keyword}
          onChange={(event) => {
            setKeyword(event.target.value);
            setPage(1);
          }}
        />
        <AdminSelect
          aria-label="角色筛选"
          value={role}
          onChange={(event) => {
            setRole(event.target.value as typeof role);
            setPage(1);
          }}
        >
          <option value="all">全部角色</option>
          <option value="USER">用户</option>
          <option value="ADMIN">管理员</option>
        </AdminSelect>
        <AdminSelect
          aria-label="状态筛选"
          value={status}
          onChange={(event) => {
            setStatus(event.target.value as typeof status);
            setPage(1);
          }}
        >
          <option value="all">全部状态</option>
          <option value="active">正常</option>
          <option value="disabled">已禁用</option>
          <option value="deleted">已删除</option>
        </AdminSelect>
      </div>

      {usersQuery.isPending ? (
        <LoadingBlock label="正在载入用户…" />
      ) : usersQuery.isError ? (
        <ErrorState error={usersQuery.error} onRetry={() => void usersQuery.refetch()} />
      ) : (
        <>
          <RowList>
            {usersQuery.data.items.length === 0 ? (
              <EmptyRow>没有匹配的用户。</EmptyRow>
            ) : (
              usersQuery.data.items.map((user) => (
                <Row
                  key={user.id}
                  onClick={() => setDrawerUserId(user.id)}
                  className="group"
                  cols="grid-cols-[minmax(0,1fr)_84px_78px_92px_58px] gap-3.5 max-[760px]:grid-cols-[minmax(0,1fr)_auto_auto] max-[760px]:gap-2.5"
                >
                  <div className="flex min-w-0 items-center gap-3">
                    <span
                      aria-hidden="true"
                      className="flex h-7 w-7 flex-none items-center justify-center rounded-full border border-rule font-sans text-[11px] text-ink-muted"
                    >
                      {[...user.username][0] ?? "用"}
                    </span>
                    <div className="min-w-0">
                      <p className="truncate text-[13px] text-ink transition-colors group-hover:text-vermilion">
                        {user.username}
                      </p>
                      <p className="mt-1 truncate font-mono text-[10px] text-ink-faint">
                        {user.email ?? "未绑定邮箱"}
                      </p>
                    </div>
                  </div>
                  <RoleBadge admin={user.role === "ADMIN"} />
                  <LiveMark on={!user.deleted && user.enabled}>
                    {user.deleted ? "已删除" : user.enabled ? "正常" : "已禁用"}
                  </LiveMark>
                  <span className="text-right font-mono text-[11px] text-ink-muted max-[760px]:hidden">
                    {user.lastLoginAt ? relativeDayLabel(user.lastLoginAt) : "从未登录"}
                  </span>
                  <span className="text-right font-mono text-[11px] text-ink-muted max-[760px]:hidden">
                    {user.attemptsCount ?? "—"}
                  </span>
                </Row>
              ))
            )}
          </RowList>
          <Pagination
            page={usersQuery.data.page}
            size={usersQuery.data.size}
            total={usersQuery.data.total}
            totalPages={usersQuery.data.totalPages}
            hasNext={usersQuery.data.hasNext}
            onPageChange={setPage}
            onSizeChange={(next) => {
              setSize(next);
              setPage(1);
            }}
          />
        </>
      )}

      <p className="mt-5 font-mono text-[11px] leading-[1.7] tracking-[0.06em] text-ink-faint">
        管理员能看到练习记录与答题原文，但看不到密码；导出、删除、禁用都会记进操作日志。
      </p>

      {drawerUser ? (
        <UserDrawer
          key={drawerUser.id}
          user={drawerUser}
          onClose={() => setDrawerUserId(null)}
          requestReason={(request) => setReasonRequest(request)}
          onRawAnswer={(view) => setRawAnswer(view)}
          onSettled={invalidateUsers}
          toast={toast.show}
        />
      ) : null}

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

      <Modal
        open={rawAnswer !== null}
        onClose={() => setRawAnswer(null)}
        kicker="作答原文 · 已记入审计"
        title={<span className="font-serif text-lg leading-[1.55]">{rawAnswer?.zh}</span>}
      >
        <div className="mt-4">
          <p className="label">用户译文</p>
          <p className="mt-2 font-serif text-base leading-[1.75] text-ink">
            {rawAnswer?.answerText}
          </p>
          <p className="mt-[18px] label">参考译文</p>
          <p className="mt-2 font-serif text-base leading-[1.75] text-ink-muted">
            {rawAnswer?.ref ?? "—"}
          </p>
          {rawAnswer?.score !== null && rawAnswer?.score !== undefined ? (
            <p className="mt-4 font-mono text-[11px] text-ink-faint">判分 {rawAnswer.score} 分</p>
          ) : null}
        </div>
        <div className="mt-[22px] flex justify-end">
          <button type="button" className="btn primary" onClick={() => setRawAnswer(null)}>
            关闭
          </button>
        </div>
      </Modal>

      {toast.node}
    </div>
  );
}

/** 用户抽屉的回调集合，由 UsersPage 提供共享的弹窗与失效逻辑。 */
interface UserDrawerProps {
  user: AdminUser;
  onClose: () => void;
  requestReason: (request: ReasonRequest) => void;
  onRawAnswer: (view: RawAnswerView) => void;
  onSettled: () => void;
  toast: (message: string) => void;
}

/**
 * 用户抽屉：四项指标、近 30 天热区、最近练习、角色变更与账户操作。
 * 边界：不能停用/删除自己；最后一个管理员不能降级或停用（服务端同样校验）。
 */
function UserDrawer({
  user,
  onClose,
  requestReason,
  onRawAnswer,
  onSettled,
  toast,
}: UserDrawerProps) {
  const me = useQuery(meQueryOptions);
  const attemptsQuery = useQuery({
    queryKey: ["admin", "users", user.id, "attempts"],
    queryFn: ({ signal }) => getUserAttempts(user.id, signal),
  });
  // 活跃管理员总数：size=1 只取 total，用于「最后一个管理员」的边界判断。
  const adminCountQuery = useQuery({
    queryKey: ["admin", "users", "active-admin-count"],
    queryFn: ({ signal }) =>
      getAdminUsers({ role: "ADMIN", status: "active", page: 1, size: 1 }, signal),
  });
  const auditQuery = useQuery({
    queryKey: queryKeys.admin.auditLogs({ keyword: user.username, size: 5 }),
    queryFn: ({ signal }) => getAuditLogs({ keyword: user.username, page: 1, size: 5 }, signal),
  });

  const isSelf = me.data?.id === user.id;
  const activeAdmins = adminCountQuery.data?.total ?? 2;
  const lastAdmin = user.role === "ADMIN" && activeAdmins <= 1;
  const roleLocked = isSelf || lastAdmin;

  const roleMutation = useMutation({
    mutationFn: (input: { role: "USER" | "ADMIN"; reason: string }) =>
      updateUserRole(user.id, input),
    onSuccess: (updated) => {
      onSettled();
      toast(`已改为${updated.role === "ADMIN" ? "管理员" : "用户"}`);
    },
    onError: (error) => toast(error instanceof Error ? error.message : "操作失败"),
  });
  const statusMutation = useMutation({
    mutationFn: (input: { enabled: boolean; reason: string }) => updateUserStatus(user.id, input),
    onSuccess: (updated) => {
      onSettled();
      toast(updated.enabled ? "已恢复" : "已停用");
    },
    onError: (error) => toast(error instanceof Error ? error.message : "操作失败"),
  });
  const deleteMutation = useMutation({
    mutationFn: (reason: string) => deleteAdminUser(user.id, reason),
    onSuccess: () => {
      onSettled();
      onClose();
      toast("已进入 30 天冷静期（演示）");
    },
    onError: (error) => toast(error instanceof Error ? error.message : "操作失败"),
  });
  const demoAuditMutation = useMutation({
    mutationFn: (input: { action: string; targetId: string; reason: string }) =>
      appendAuditEvent(input),
    onSuccess: () => onSettled(),
    onError: (error) => toast(error instanceof Error ? error.message : "操作失败"),
  });
  const rawMutation = useMutation({
    mutationFn: (input: { attemptId: string; reason: string; zh: string; ref: string | null }) =>
      getRawAttempt(input.attemptId, input.reason),
    onSuccess: (raw, variables) => {
      onRawAnswer({
        zh: variables.zh,
        ref: variables.ref,
        answerText: answerToText(raw.answer),
        score: raw.grading?.score ?? null,
      });
      onSettled();
    },
    onError: (error) => toast(error instanceof Error ? error.message : "查看失败"),
  });

  const metrics = attemptsQuery.data?.metrics;
  const records = attemptsQuery.data?.items ?? [];
  const heat = attemptsQuery.data?.heat ?? [];

  return (
    <Drawer
      open
      onClose={onClose}
      kicker={`用户 · ${user.id.toUpperCase()}`}
      title={user.username}
      meta={`${user.email ?? "未绑定邮箱"} · ${user.role === "ADMIN" ? "管理员" : "用户"} · ${user.deleted ? "已删除" : user.enabled ? "正常" : `已禁用（${user.disabledReason ?? "未记录原因"}）`}`}
    >
      {metrics ? (
        <MiniMetrics
          items={[
            { value: metrics.totalAttempts, label: "累计答题" },
            { value: metrics.avgScore === null ? "—" : `${metrics.avgScore}%`, label: "正确率" },
            { value: `${metrics.streakDays} 天`, label: "连续" },
            { value: `${metrics.activeDays30} 天`, label: "近 30 天活跃" },
          ]}
        />
      ) : (
        <LoadingBlock label="正在载入练习记录…" rows={2} />
      )}

      <p className="section-title mt-[30px]">近 30 天</p>
      <HeatGrid values={heat} />

      <p className="section-title mt-[30px]">最近练习</p>
      {records.length === 0 ? (
        <p className="py-3 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
          这个用户还没有练习记录。
        </p>
      ) : (
        records.map((record) => (
          <div
            key={record.attemptId}
            className="flex items-baseline justify-between gap-3.5 border-b border-rule py-[11px] text-xs text-ink-muted"
          >
            <div className="min-w-0">
              <p className="truncate font-serif text-[13px] text-ink">{record.zh}</p>
              <p className="mt-[5px] font-mono text-[9.5px] tracking-[0.06em] text-ink-faint">
                {record.mode === "FREE" ? "整句" : "填空"} ·{" "}
                {record.score === null ? "已看答案" : `${record.score} 分`} ·{" "}
                {relativeDayLabel(record.createdAt)}
              </p>
            </div>
            <button
              type="button"
              className="linkish flex-none text-[11px]"
              disabled={rawMutation.isPending}
              onClick={() =>
                requestReason({
                  title: "查看作答",
                  description: "答题原文默认不可见。查看会写入审计日志，请选择原因。",
                  options: ["评分申诉", "内容纠错", "其他"],
                  onConfirm: (reason) =>
                    rawMutation.mutate({
                      attemptId: record.attemptId,
                      reason,
                      zh: record.zh,
                      ref: record.ref,
                    }),
                })
              }
            >
              查看作答
            </button>
          </div>
        ))
      )}

      <p className="section-title mt-[30px]">账户</p>
      <DrawerField label="角色">
        <AdminSelect
          aria-label="用户角色"
          value={user.role}
          disabled={roleLocked}
          onChange={(event) => {
            const nextRole = event.target.value as "USER" | "ADMIN";
            requestReason({
              title: "变更角色",
              description: `将 ${user.username} 改为${nextRole === "ADMIN" ? "管理员" : "用户"}，确认？`,
              options: ["误操作纠正", "团队调整", "其他"],
              onConfirm: (reason) => roleMutation.mutate({ role: nextRole, reason }),
            });
          }}
        >
          <option value="USER">用户</option>
          <option value="ADMIN">管理员</option>
        </AdminSelect>
      </DrawerField>
      <div className="mt-[22px] flex flex-wrap gap-4">
        <button
          type="button"
          className="linkish"
          disabled={isSelf || statusMutation.isPending}
          onClick={() => {
            if (user.enabled) {
              requestReason({
                title: "停用账号",
                description: "停用后立即踢下线，用户会看到停用提示与申诉邮箱。",
                options: ["违规", "用户申请", "长期未使用", "其他"],
                onConfirm: (reason) => statusMutation.mutate({ enabled: false, reason }),
              });
              return;
            }
            requestReason({
              title: "恢复账号",
              description: `恢复 ${user.username} 的登录与练习权限。`,
              options: ["用户申请恢复", "误操作纠正", "其他"],
              onConfirm: (reason) => statusMutation.mutate({ enabled: true, reason }),
            });
          }}
        >
          {user.enabled ? "停用账号" : "恢复账号"}
        </button>
        <button
          type="button"
          className="linkish"
          disabled={demoAuditMutation.isPending}
          onClick={() =>
            requestReason({
              title: "重置密码",
              description: "将生成 30 分钟有效的一次性重置链接，用户设置新密码后全部会话被吊销。",
              options: ["用户忘记密码", "安全原因", "其他"],
              onConfirm: (reason) => {
                demoAuditMutation.mutate({
                  action: "RESET_PASSWORD",
                  targetId: user.id,
                  reason,
                });
                toast("已发送 30 分钟有效的一次性链接（演示）");
              },
            })
          }
        >
          重置密码
        </button>
        <button
          type="button"
          className="linkish"
          disabled={demoAuditMutation.isPending}
          onClick={() =>
            requestReason({
              title: "导出数据",
              description: "导出该用户的账号与答题记录；导出动作与原因会写入审计日志。",
              options: ["用户申请", "运维备份", "其他"],
              onConfirm: (reason) => {
                demoAuditMutation.mutate({
                  action: "EXPORT_USER_DATA",
                  targetId: user.id,
                  reason,
                });
                toast("已生成数据导出包（演示）");
              },
            })
          }
        >
          导出数据
        </button>
        <button
          type="button"
          className="linkish"
          disabled={isSelf || deleteMutation.isPending}
          onClick={() =>
            requestReason({
              title: "删除账号",
              description: "软删 30 天后彻底删除，期间可恢复；答题流水与审计保留。",
              options: ["用户申请", "违规", "其他"],
              onConfirm: (reason) => deleteMutation.mutate(reason),
            })
          }
        >
          删除账号
        </button>
      </div>
      {isSelf ? (
        <p className="mt-3 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
          不能修改或停用自己的账号。
        </p>
      ) : null}
      {lastAdmin && !isSelf ? (
        <p className="mt-3 font-mono text-[11px] tracking-[0.06em] text-ink-faint">
          这是最后一个管理员，不能降级或停用。
        </p>
      ) : null}

      <p className="section-title mt-[30px]">审计</p>
      <ul className="m-0 list-none p-0">
        {(auditQuery.data?.items ?? []).length === 0 ? (
          <li className="flex items-center justify-between gap-3 border-b border-rule py-[9px] text-[11.5px] text-ink-muted">
            <span>暂无相关记录</span>
            <span className="font-mono text-[9.5px] text-ink-faint">—</span>
          </li>
        ) : (
          auditQuery.data?.items.map((log) => (
            <li
              key={log.id}
              className="flex items-center justify-between gap-3 border-b border-rule py-[9px] text-[11.5px] text-ink-muted"
            >
              <span className="min-w-0 truncate">
                {actionLabel(log.action)}
                {log.reason ? ` · ${log.reason}` : ""}
              </span>
              <span className="flex-none font-mono text-[9.5px] text-ink-faint">
                {relativeDayLabel(log.createdAt)}
              </span>
            </li>
          ))
        )}
      </ul>
    </Drawer>
  );
}
