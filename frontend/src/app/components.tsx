import { BatteryCharging, CircleHelp, RefreshCw, Zap } from 'lucide-react'
import type React from 'react'
import type { ReactNode } from 'react'

export type Notice = { kind: 'success' | 'error' | 'info'; text: string }

export function PageHeader({ eyebrow, title, detail, action }: { eyebrow?: string; title: string; detail?: string; action?: ReactNode }) {
  return <header className="page-heading">
    <div>{eyebrow && <p className="eyebrow">{eyebrow}</p>}<h1>{title}</h1>{detail && <p className="page-detail">{detail}</p>}</div>
    {action && <div className="heading-action">{action}</div>}
  </header>
}

export function NoticeBox({ notice }: { notice: Notice }) {
  return <div className={`notice notice-${notice.kind}`} role={notice.kind === 'error' ? 'alert' : 'status'}>
    <CircleHelp size={18} aria-hidden="true" /><span>{notice.text}</span>
  </div>
}

export function Loading({ label }: { label: string }) {
  return <div className="loading-panel" role="status" aria-live="polite"><span className="spinner" aria-hidden="true" />{label}</div>
}

export function LoadError({ onRetry }: { onRetry: () => void }) {
  return <div className="empty-panel error-panel" role="alert">
    <strong>Chưa tải được dữ liệu</strong><p>Kiểm tra kết nối với hệ thống rồi thử lại.</p>
    <button className="button button-secondary" onClick={onRetry}><RefreshCw size={16} aria-hidden="true" />Tải lại</button>
  </div>
}

export function EmptyState({ title, detail, action }: { title: string; detail: string; action?: ReactNode }) {
  return <div className="empty-panel"><span className="empty-glyph" aria-hidden="true"><BatteryCharging size={22} /></span>
    <strong>{title}</strong><p>{detail}</p>{action}</div>
}

const statusLabels: Record<string, string> = {
  AVAILABLE: 'Trống', RESERVED: 'Đã đặt trước', CHARGING: 'Đang sạc', FAULTED: 'Có sự cố', OFFLINE: 'Mất kết nối', DISABLED: 'Tạm khóa / bảo trì',
  CONFIRMED: 'Đã xác nhận', IN_USE: 'Đang sử dụng', COMPLETED: 'Đã hoàn tất', CANCELLED: 'Đã hủy', EXPIRED: 'Hết hạn', UNSERVICEABLE: 'Không thể phục vụ',
  START_REQUESTED: 'Đang chờ thiết bị bắt đầu', STOP_REQUESTED: 'Đang chờ thiết bị dừng', PENDING: 'Đang chờ', SENT: 'Đã gửi lệnh', ACKNOWLEDGED: 'Đã xác nhận', TIMED_OUT: 'Quá thời gian chờ', FAILED: 'Thất bại', SUCCEEDED: 'Đã thanh toán', UNPAID: 'Chưa thanh toán', OPEN: 'Mới', ASSIGNED: 'Đã phân công', REPAIR_REPORTED: 'Nhân viên báo đã sửa', RESOLVED: 'Đã xử lý',
}

export function StatusBadge({ value }: { value: string }) {
  const kind = value === 'AVAILABLE' || value === 'SUCCEEDED' || value === 'COMPLETED' || value === 'RESOLVED' ? 'good'
    : value === 'FAULTED' || value === 'OFFLINE' || value === 'FAILED' || value === 'UNSERVICEABLE' ? 'bad'
      : value === 'CHARGING' || value === 'IN_USE' ? 'active' : 'pending'
  return <span className={`status-badge status-${kind}`}><span className="status-dot" aria-hidden="true" />{statusLabels[value] ?? 'Chưa xác định'}</span>
}

export function ChargeRail({ value, detail }: { value: number; detail?: ReactNode }) {
  const percent = Math.max(0, Math.min(100, value))
  return <div className="charge-meter-wrap" data-variant="circle">
    <div className="charge-meter-label"><span>Mức pin</span><strong>{percent}%</strong></div>
    <div className="charge-meter" role="meter" aria-label="Mức pin" aria-valuemin={0} aria-valuemax={100} aria-valuenow={percent} style={{ '--charge-percent': `${percent}%` } as React.CSSProperties}>
      <span className="charge-meter-fill" aria-hidden="true" />
      <span className="charge-meter-center"><Zap size={19} aria-hidden="true" /><span className="charge-meter-value" aria-hidden="true">{percent}%</span>{detail && <span className="charge-meter-detail">{detail}</span>}</span>
    </div>
  </div>
}

export function DataTable({ children, label }: { children: ReactNode; label: string }) {
  return <div className="table-scroll" role="region" aria-label={label} tabIndex={0}><table>{children}</table></div>
}

export function Field({ label, error, hint, ...props }: React.InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string; hint?: string }) {
  const id = props.id ?? `field-${label.toLocaleLowerCase('vi').replace(/[^a-z0-9]+/g, '-')}`
  const errorId = `${id}-error`
  return <label className="field" htmlFor={id}>
    <span className="field-label">{label}</span>
    <input {...props} id={id} aria-invalid={Boolean(error)} aria-describedby={[hint ? `${id}-hint` : '', error ? errorId : ''].filter(Boolean).join(' ') || undefined} />
    {hint && <span className="field-hint" id={`${id}-hint`}>{hint}</span>}
    {error && <span className="field-error" id={errorId}>{error}</span>}
  </label>
}

export function FormError({ message }: { message: string }) { return message ? <p className="form-error" role="alert">{message}</p> : null }

export function ActionButton({ children, busy, className = '', ...props }: React.ButtonHTMLAttributes<HTMLButtonElement> & { busy?: boolean }) {
  return <button {...props} className={`button ${className}`} disabled={props.disabled || busy}>
    {busy ? <><span className="spinner spinner-small" aria-hidden="true" />Đang xử lý…</> : children}
  </button>
}
