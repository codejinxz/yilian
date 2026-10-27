import {
  type ComponentProps,
  type ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { cn } from "@/lib/utils";

/**
 * 管理端共享 UI：分节、指标、柱状、行列表、抽屉、弹窗与「填原因」弹窗。
 * 样式语言（prototype/index.html 的同名类）：纸面分隔线 + 等宽小号边注 + 朱红强调，
 * 数值逐项取自原型 CSS；壳层全局类（section-title / btn / linkish）优先直接消费。
 */

/**
 * 管理端分节：等宽小号标题 + 可选右侧操作 + 内容（原型 .section / .section-title）。
 * @param props.title 边注标题
 * @param props.action 标题右侧操作（如「新建」linkish）
 * @param props.className 追加到根节点的类名
 */
export function AdminSection({
  title,
  action,
  children,
  className,
}: {
  title: string;
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  return (
    <section className={cn("mt-[52px]", className)}>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="section-title mb-0">{title}</h2>
        {action}
      </div>
      <div className="mt-4">{children}</div>
    </section>
  );
}

/**
 * 大数字指标行（原型 .metrics / .metric）：一排 4 个（窄屏 2 个），衬线 34px + 底部分隔线。
 * @param props.items 数值与标签；value 可带 <small> 单位
 */
export function MetricRow({ items }: { items: Array<{ value: ReactNode; label: string }> }) {
  return (
    <div className="grid grid-cols-2 gap-x-6 gap-y-8 border-b border-rule pb-[30px] pt-1 sm:grid-cols-4">
      {items.map((item) => (
        <div key={item.label}>
          <p className="font-serif text-[34px] font-medium leading-none text-ink">{item.value}</p>
          <p className="mt-[11px] font-mono text-[10px] tracking-[0.14em] text-ink-faint">
            {item.label}
          </p>
        </div>
      ))}
    </div>
  );
}

/**
 * 迷你指标（抽屉用，原型 .mini-metrics）：两列小格，上下分隔线夹住。
 * @param props.items 数值与标签
 */
export function MiniMetrics({ items }: { items: Array<{ value: ReactNode; label: string }> }) {
  return (
    <div className="mt-6 grid grid-cols-2 gap-x-[18px] gap-y-6 border-y border-rule py-[22px]">
      {items.map((item) => (
        <div key={item.label}>
          <p className="font-serif text-2xl font-medium leading-none text-ink">{item.value}</p>
          <p className="mt-[7px] font-mono text-[9px] tracking-[0.14em] text-ink-faint">
            {item.label}
          </p>
        </div>
      ))}
    </div>
  );
}

/**
 * 柱状图（原型 .bars）：最后一根为今天（朱红），其余有值的为浅朱红。
 * @param props.values 从旧到新的数值序列
 * @param props.titles 每根柱子的悬停说明，与 values 等长
 */
export function Bars({ values, titles }: { values: number[]; titles?: string[] }) {
  const max = Math.max(1, ...values);
  return (
    <div className="flex h-[92px] items-end gap-[5px]" aria-hidden="true">
      {values.map((value, index) => (
        <i
          // biome-ignore lint/suspicious/noArrayIndexKey: 柱状序列按位置渲染且长度固定，index 即稳定标识。
          key={index}
          title={titles?.[index]}
          className={cn(
            "min-h-[2px] flex-1 transition-[height] duration-500",
            index === values.length - 1
              ? "bg-vermilion"
              : value > 0
                ? "bg-vermilion/35"
                : "bg-rule",
          )}
          style={{ height: `${Math.max(8, Math.round((value / max) * 100))}%` }}
        />
      ))}
    </div>
  );
}

/** 柱状图两端的说明文字（原型 .bars-cap）。 */
export function BarsCaption({ left, right }: { left: string; right: string }) {
  return (
    <div className="mt-2.5 flex justify-between font-mono text-[9px] tracking-[0.14em] text-ink-faint">
      <span>{left}</span>
      <span>{right}</span>
    </div>
  );
}

/**
 * 水平占比条（原型 .dist-row）：标签 + 细条 + 右侧说明。
 * @param props.label 行名、ratio 占比 0-1、note 右侧文字
 */
export function DistRow({
  label,
  ratio = 1,
  note,
}: {
  label: string;
  ratio?: number;
  note: string;
}) {
  return (
    <div className="grid grid-cols-[72px_1fr_auto] items-center gap-4 text-xs text-ink-muted">
      <span>{label}</span>
      <span className="relative h-[5px] bg-rule">
        <i
          className="absolute inset-y-0 left-0 bg-vermilion opacity-70"
          style={{ width: `${Math.round(Math.min(1, Math.max(0, ratio)) * 100)}%` }}
        />
      </span>
      {/* note 不限宽不折行省略：长说明允许自然折行，避免被裁掉。 */}
      <span className="font-mono text-[10px] text-ink-faint">{note}</span>
    </div>
  );
}

/** 行状态标记（原型 .row-state）：ok（青绿）/ warn（朱红）/ muted（灰）。 */
export function StatePill({
  tone = "muted",
  children,
}: {
  tone?: "ok" | "warn" | "muted";
  children: ReactNode;
}) {
  const tones = {
    ok: "border-moss/40 text-moss",
    warn: "border-vermilion/40 text-vermilion",
    muted: "border-rule text-ink-faint",
  } as const;
  return (
    <span
      className={cn(
        "inline-flex items-center whitespace-nowrap rounded-[2px] border px-2 py-1 font-mono text-[10px] tracking-[0.1em]",
        tones[tone],
      )}
    >
      {children}
    </span>
  );
}

/**
 * 管理端行列表与行（原型 .row-list li）：默认三列（内容 / 状态 / 操作）+ 20px 列距。
 * cn 是纯拼接（无 tailwind-merge），列模板与列距有页面级差异时必须走 cols 整体替换，
 * 不能靠 className 追加同名工具类覆盖。
 * @param props.children 行内容、cols 列模板与列距（grid-cols-* + gap-*）
 */
export function RowList({ children, className }: { children: ReactNode; className?: string }) {
  return <ul className={cn("m-0 list-none p-0", className)}>{children}</ul>;
}

export function Row({
  children,
  onClick,
  className,
  cols,
}: {
  children: ReactNode;
  onClick?: () => void;
  className?: string;
  cols?: string;
}) {
  const clickable = typeof onClick === "function";
  return (
    <li
      onClick={onClick}
      onKeyDown={
        clickable
          ? (event) => {
              if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                onClick();
              }
            }
          : undefined
      }
      role={clickable ? "button" : undefined}
      tabIndex={clickable ? 0 : undefined}
      className={cn(
        "grid items-center border-b border-rule py-[18px]",
        cols ?? "grid-cols-[minmax(0,1fr)_auto_auto] gap-5",
        clickable && "cursor-pointer",
        className,
      )}
    >
      {children}
    </li>
  );
}

/** 行列表的空态（原型 .row-empty）。 */
export function EmptyRow({ children }: { children: ReactNode }) {
  return (
    <li className="border-b border-rule py-10 text-center text-xs text-ink-faint">{children}</li>
  );
}

/**
 * 行主文本 + 次行说明的标准组合（原型 .row-main / .row-meta）：
 * 主文本衬线 16px，次行 10px mono 宽字距。
 * @param props.main 主文本、meta 次行说明
 */
export function RowText({ main, meta }: { main: ReactNode; meta?: ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="truncate font-serif text-base leading-[1.5] text-ink">{main}</div>
      {meta ? (
        <div className="mt-[7px] truncate font-mono text-[10px] tracking-[0.1em] text-ink-faint">
          {meta}
        </div>
      ) : null}
    </div>
  );
}

/** 行内文字操作（原型 .row-act）：12px 灰字，悬停转朱红，无下划线。 */
export function RowAct({
  onClick,
  disabled,
  children,
}: {
  onClick: () => void;
  disabled?: boolean;
  children: ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      className="whitespace-nowrap text-xs tracking-[0.05em] text-ink-muted transition-colors hover:text-vermilion disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:text-ink-muted"
    >
      {children}
    </button>
  );
}

/**
 * 筛选搜索框（原型 .search）：无边框只有底部分隔线，flex:1、min-width 180px。
 * @param props 原生 input 属性
 */
export function AdminSearch({ className, ...props }: ComponentProps<"input">) {
  return (
    <input
      {...props}
      className={cn(
        "min-w-[180px] flex-1 border-b border-rule bg-transparent py-2 text-[13px] text-ink outline-none transition placeholder:text-ink-faint focus:border-vermilion disabled:cursor-not-allowed disabled:opacity-60",
        className,
      )}
    />
  );
}

/**
 * 筛选/表单下拉（原型 .select）：透明底、1px 分隔线、12px。
 * @param props 原生 select 属性
 */
export function AdminSelect({ className, ...props }: ComponentProps<"select">) {
  return (
    <select
      {...props}
      className={cn(
        "rounded-[2px] border border-rule bg-transparent px-2.5 py-[7px] text-xs text-ink-muted outline-none transition focus:border-ink-faint disabled:cursor-not-allowed disabled:opacity-60",
        className,
      )}
    />
  );
}

/**
 * 抽屉里的字段行（原型 .drawer-field）：左说明右控件，12px。
 * @param props.label 左侧说明、children 右侧控件
 */
export function DrawerField({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mb-4 flex items-center justify-between gap-3.5">
      <span className="text-xs text-ink-muted">{label}</span>
      {children}
    </div>
  );
}

/**
 * 状态点（原型 .live）：5px 圆点 + 10px mono 文案，off 时点转灰。
 * @param props.on 是否点亮（青绿）、children 文案
 */
export function LiveMark({ on, children }: { on: boolean; children: string }) {
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap font-mono text-[10px] text-ink-faint">
      <i
        aria-hidden="true"
        className={
          on
            ? "h-[5px] w-[5px] rounded-full bg-moss"
            : "h-[5px] w-[5px] rounded-full bg-ink-faint opacity-50"
        }
      />
      {children}
    </span>
  );
}

/**
 * 近 30 天热力格（原型 .heat）：15 列方格，五档朱红阶梯。
 * @param props.values 每天的活跃次数（0-4+，超过 4 按最深档）
 */
export function HeatGrid({ values }: { values: number[] }) {
  return (
    <div className="mt-3.5 grid grid-cols-[repeat(15,1fr)] gap-1" aria-hidden="true">
      {values.map((value, index) => (
        <i
          // biome-ignore lint/suspicious/noArrayIndexKey: 热力格按固定 30 天位置渲染，index 即稳定标识。
          key={index}
          title={`${value} 次`}
          className="aspect-square rounded-[1px]"
          style={{
            backgroundColor:
              value <= 0
                ? "var(--color-rule)"
                : value === 1
                  ? "rgba(178,58,46,.18)"
                  : value === 2
                    ? "rgba(178,58,46,.36)"
                    : value === 3
                      ? "rgba(178,58,46,.56)"
                      : "var(--color-vermilion)",
          }}
        />
      ))}
    </div>
  );
}

/**
 * 表单字段（原型 .field）：等宽小号标签 + 控件 + 可选说明。
 * @param props.label 字段名、hint 字段说明
 */
export function AdminField({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: ReactNode;
  children: ReactNode;
}) {
  return (
    <div className="mb-5">
      <p className="mb-[9px] font-mono text-[9px] tracking-[0.18em] text-ink-faint">{label}</p>
      {children}
      {hint ? <div className="mt-2 font-mono text-[10px] text-ink-faint">{hint}</div> : null}
    </div>
  );
}

/** 覆盖层共用的副作用：打开时锁 body 滚动，Escape 关闭。 */
function useOverlayOpen(open: boolean, onClose: () => void) {
  useEffect(() => {
    if (!open) {
      return;
    }
    const previous = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        onClose();
      }
    };
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.body.style.overflow = previous;
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open, onClose]);
}

/**
 * 右侧抽屉（原型 .drawer）：440px、纸底、左分隔线；题库、用户详情、异常堆栈共用。
 * @param props.open 是否打开、onClose 关闭回调、kicker 引题、title 标题、meta 标题下说明
 */
export function Drawer({
  open,
  onClose,
  kicker,
  title,
  meta,
  children,
}: {
  open: boolean;
  onClose: () => void;
  kicker?: string;
  title: ReactNode;
  meta?: ReactNode;
  children: ReactNode;
}) {
  useOverlayOpen(open, onClose);
  if (!open) {
    return null;
  }
  return (
    <div className="fixed inset-0 z-[70] flex justify-end">
      <div
        className="absolute inset-0 bg-ink/40"
        onClick={onClose}
        aria-hidden="true"
        data-testid="drawer-mask"
      />
      <aside
        role="dialog"
        aria-modal="true"
        className="relative h-full w-[440px] max-w-[94vw] overflow-y-auto border-l border-rule bg-paper px-8 pb-16 pt-[34px]"
      >
        <button
          type="button"
          onClick={onClose}
          aria-label="关闭"
          className="absolute right-5 top-5 text-lg leading-none text-ink-faint transition-colors hover:text-ink"
        >
          ×
        </button>
        {kicker ? (
          <p className="mb-2.5 font-mono text-[9px] tracking-[0.18em] text-ink-faint">{kicker}</p>
        ) : null}
        <h2 className="font-serif text-[26px] font-medium text-ink">{title}</h2>
        {meta ? (
          <p className="mt-1.5 font-mono text-[11px] tracking-[0.04em] text-ink-faint">{meta}</p>
        ) : null}
        {children}
      </aside>
    </div>
  );
}

/**
 * 居中弹窗（原型 .modal）：默认 430px，wide 760px；纸底、1px 分隔线。
 * @param props.open 是否打开、onClose 关闭回调、kicker 引题、title 标题、wide 宽版
 */
export function Modal({
  open,
  onClose,
  kicker,
  title,
  wide = false,
  children,
}: {
  open: boolean;
  onClose: () => void;
  kicker?: string;
  title?: ReactNode;
  wide?: boolean;
  children: ReactNode;
}) {
  useOverlayOpen(open, onClose);
  if (!open) {
    return null;
  }
  return (
    <div className="fixed inset-0 z-[70] grid place-items-center px-6">
      <div
        className="absolute inset-0 bg-ink/40"
        onClick={onClose}
        aria-hidden="true"
        data-testid="modal-mask"
      />
      <div
        role="dialog"
        aria-modal="true"
        className={cn(
          "relative max-h-[90vh] overflow-y-auto border border-rule bg-paper pb-[22px] pl-[26px] pr-[26px] pt-[26px]",
          wide ? "w-[760px] max-w-[94vw]" : "w-[430px] max-w-[94vw]",
        )}
      >
        <button
          type="button"
          onClick={onClose}
          aria-label="关闭"
          className="absolute right-5 top-5 text-lg leading-none text-ink-faint transition-colors hover:text-ink"
        >
          ×
        </button>
        {kicker ? (
          <p className="mb-2.5 font-mono text-[9px] tracking-[0.18em] text-ink-faint">{kicker}</p>
        ) : null}
        {title ? <h3 className="mb-2 font-serif text-xl font-medium text-ink">{title}</h3> : null}
        {children}
      </div>
    </div>
  );
}

/**
 * 「填原因」弹窗（原型 askReason）：删除、停用、查看原文、角色变更等所有敏感写操作的统一确认层。
 * 预设原因单选 + 「其他」自定义输入；确认时把最终原因交给 onConfirm。
 * @param props.open 是否打开、title 标题、description 说明、options 预设原因
 * @param props.onCancel 取消回调、onConfirm(reason) 确认回调、busy 提交中
 */
export function ReasonDialog({
  open,
  title,
  description,
  options,
  onCancel,
  onConfirm,
  busy = false,
}: {
  open: boolean;
  title: string;
  description: string;
  options: string[];
  onCancel: () => void;
  onConfirm: (reason: string) => void;
  busy?: boolean;
}) {
  const CUSTOM = "其他";
  const [preset, setPreset] = useState<string>(options[0] ?? CUSTOM);
  const [custom, setCustom] = useState("");
  // 每次打开重置到第一个预设，避免上一次的选择带到下一个操作。
  useEffect(() => {
    if (open) {
      setPreset(options[0] ?? CUSTOM);
      setCustom("");
    }
  }, [open, options]);
  const reason = preset === CUSTOM ? custom.trim() : preset;
  if (!open) {
    return null;
  }
  return (
    <Modal open={open} onClose={onCancel} title={title}>
      <p className="mb-[18px] text-[12.5px] leading-[1.75] text-ink-muted">{description}</p>
      <div className="space-y-2.5">
        {[...options, CUSTOM].map((option) => (
          <label
            key={option}
            className="flex cursor-pointer items-start gap-2.5 text-[13px] leading-[1.5] text-ink-muted"
          >
            <input
              type="radio"
              name="reason-option"
              className="mt-[3px] accent-vermilion"
              checked={preset === option}
              onChange={() => setPreset(option)}
            />
            <span className="text-ink">{option}</span>
          </label>
        ))}
        {preset === CUSTOM ? (
          <input
            // biome-ignore lint/a11y/noAutofocus: 选「其他」即聚焦自定义输入，焦点仍在弹窗内，与原型一致。
            autoFocus
            aria-label="自定义原因"
            placeholder="填写具体原因"
            value={custom}
            onChange={(event) => setCustom(event.target.value)}
            className="w-full border-b border-rule bg-transparent py-2 text-[15px] text-ink outline-none transition placeholder:text-ink-faint focus:border-vermilion"
          />
        ) : null}
      </div>
      <div className="mt-[22px] flex justify-end gap-3">
        <button type="button" className="btn ghost" onClick={onCancel} disabled={busy}>
          取消
        </button>
        <button
          type="button"
          className="btn primary"
          onClick={() => onConfirm(reason)}
          disabled={busy || reason === ""}
        >
          {busy ? "提交中…" : "确认"}
        </button>
      </div>
    </Modal>
  );
}

/**
 * 页内轻提示 hook：替代全局 toast 基础设施，role=status 供读屏播报，3 秒自动消失。
 * @returns show(text) 触发提示；node 渲染在页面任意位置
 */
export function useToastMessage(): { show: (text: string) => void; node: ReactNode } {
  const [message, setMessage] = useState<string | null>(null);
  const timerRef = useRef<number | null>(null);
  useEffect(
    () => () => {
      if (timerRef.current !== null) {
        window.clearTimeout(timerRef.current);
      }
    },
    [],
  );
  const show = useCallback((text: string) => {
    setMessage(text);
    if (timerRef.current !== null) {
      window.clearTimeout(timerRef.current);
    }
    timerRef.current = window.setTimeout(() => setMessage(null), 3000);
  }, []);
  const node = message ? (
    <div
      role="status"
      className="fixed bottom-20 left-1/2 z-[80] -translate-x-1/2 rounded-sm bg-ink px-4 py-2 text-[12.5px] text-paper"
    >
      {message}
    </div>
  ) : null;
  return { show, node };
}
