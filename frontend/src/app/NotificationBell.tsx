import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Bell, CheckCheck } from 'lucide-react'
import { api } from '../api/client'
import type { User } from '../api/client'
import { incidentLabel } from './vietnamese'

type Notification = { id: string; title: string; detail: string; href: string; createdAt: string; read: boolean }
type Snapshot = { sessions: Map<string, string>; bookings: Map<string, string>; incidents: Map<string, string> }
const MAX_NOTIFICATIONS = 50

function readNotifications(key: string): Notification[] {
  try {
    const value: unknown = JSON.parse(localStorage.getItem(key) ?? '[]')
    return Array.isArray(value) ? value.filter(item => item && typeof item.id === 'string' && typeof item.title === 'string').slice(0, MAX_NOTIFICATIONS) : []
  } catch { return [] }
}

function statusMessage(status: string): string {
  return ({ OPEN: 'Mới tiếp nhận', ASSIGNED: 'Đã phân công nhân viên', REPAIR_REPORTED: 'Nhân viên báo đã sửa', RESOLVED: 'Đã xử lý' } as Record<string, string>)[status] ?? status
}

function relativeTime(timestamp: string): string {
  const minutes = Math.max(0, Math.floor((Date.now() - new Date(timestamp).getTime()) / 60_000))
  if (minutes < 1) return 'Vừa xong'
  if (minutes < 60) return `${minutes} phút trước`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} giờ trước`
  return `${Math.floor(hours / 24)} ngày trước`
}

export function NotificationBell({ user }: { user: User }) {
  const storageKey = `campus-charge-notifications:${user.role}:${user.userId}`
  const [items, setItems] = useState<Notification[]>(() => readNotifications(storageKey))
  const [open, setOpen] = useState(false)
  const snapshot = useRef<Snapshot | null>(null)
  const root = useRef<HTMLDivElement>(null)
  const unread = items.reduce((count, item) => count + Number(!item.read), 0)

  useEffect(() => {
    try { localStorage.setItem(storageKey, JSON.stringify(items.slice(0, MAX_NOTIFICATIONS))) } catch { /* Keep notifications in memory when browser storage is unavailable. */ }
  }, [items, storageKey])

  useEffect(() => {
    let active = true
    let timer = 0
    const publish = (next: Notification[]) => {
      if (!next.length || !active) return
      setItems(current => {
        const existing = new Set(current.map(item => item.id))
        return [...next.filter(item => !existing.has(item.id)), ...current].slice(0, MAX_NOTIFICATIONS)
      })
    }
    const poll = async () => {
      try {
        if (user.role === 'ADMIN') {
          const incidents = await api.incidents()
          if (!active) return
          const current = new Map(incidents.map(item => [item.incidentId, item.status]))
          if (snapshot.current) {
            const notices: Notification[] = []
            for (const item of incidents) {
              const oldStatus = snapshot.current.incidents.get(item.incidentId)
              if (!oldStatus && item.status !== 'RESOLVED') notices.push({ id: `incident:${item.incidentId}:new`, title: 'Có sự cố mới', detail: `${item.stationName} · ${incidentLabel(item.description)}`, href: '/incidents', createdAt: item.reportedAt, read: false })
              else if (oldStatus !== 'REPAIR_REPORTED' && item.status === 'REPAIR_REPORTED') notices.push({ id: `incident:${item.incidentId}:repair`, title: 'Nhân viên báo đã sửa', detail: `${item.stationName} · ${incidentLabel(item.description)}`, href: '/incidents', createdAt: item.repairReportedAt ?? new Date().toISOString(), read: false })
            }
            publish(notices)
          }
          snapshot.current = { sessions: new Map(), bookings: new Map(), incidents: current }
          return
        }

        const [sessions, bookings, incidents] = await Promise.all([api.sessions(), api.bookings(), api.myIncidents()])
        if (!active) return
        const current: Snapshot = {
          sessions: new Map(sessions.map(item => [item.sessionId, `${item.status}|${item.paymentStatus ?? ''}`])),
          bookings: new Map(bookings.map(item => [item.bookingId, item.status])),
          incidents: new Map(incidents.map(item => [item.incidentId, item.status])),
        }
        const previous = snapshot.current
        if (previous) {
          const notices: Notification[] = []
          for (const item of sessions) {
            const old = previous.sessions.get(item.sessionId)
            const state = current.sessions.get(item.sessionId)
            const oldStatus = old?.split('|')[0]
            const oldPayment = old?.split('|')[1]
            const href = `/sessions/${item.sessionId}`
            if (oldStatus !== 'CHARGING' && item.status === 'CHARGING') notices.push({ id: `session:${item.sessionId}:charging`, title: 'Phiên sạc đã bắt đầu', detail: `${item.portName ?? 'Trụ sạc'} · ${item.batteryPercent}% pin`, href, createdAt: new Date().toISOString(), read: false })
            if (oldStatus !== 'COMPLETED' && item.status === 'COMPLETED') notices.push({ id: `session:${item.sessionId}:completed`, title: item.completionReason === 'DEVICE_DISCONNECTED' ? 'Phiên sạc kết thúc do mất kết nối' : 'Phiên sạc đã hoàn tất', detail: item.completionReason === 'DEVICE_DISCONNECTED' ? `${item.portName ?? 'Trụ sạc'} · chốt theo ${item.energyKwh} kWh cuối đã nhận; cổng đang khóa` : `${item.portName ?? 'Trụ sạc'} · ${item.energyKwh} kWh`, href, createdAt: item.endedAt ?? new Date().toISOString(), read: false })
            if (oldPayment !== 'SUCCEEDED' && item.paymentStatus === 'SUCCEEDED') notices.push({ id: `session:${item.sessionId}:paid`, title: 'Thanh toán thành công', detail: `${item.portName ?? 'Trụ sạc'} · ${Math.round(item.totalFee ?? 0).toLocaleString('vi-VN')} ₫`, href, createdAt: new Date().toISOString(), read: false })
            if (old && state !== old && item.status === 'STOP_REQUESTED') notices.push({ id: `session:${item.sessionId}:stopping`, title: 'Đang dừng phiên sạc', detail: `${item.portName ?? 'Trụ sạc'} · chờ thiết bị xác nhận`, href, createdAt: new Date().toISOString(), read: false })
          }
          for (const item of bookings) {
            const oldStatus = previous.bookings.get(item.bookingId)
            if (!oldStatus && item.status === 'CONFIRMED') notices.push({ id: `booking:${item.bookingId}:confirmed`, title: 'Đặt chỗ đã được xác nhận', detail: `Thời gian đến ${new Date(item.startAt).toLocaleString('vi-VN')}`, href: '/bookings', createdAt: new Date().toISOString(), read: false })
            else if (oldStatus && oldStatus !== item.status) notices.push({ id: `booking:${item.bookingId}:${item.status}`, title: `Đặt chỗ ${statusMessage(item.status).toLowerCase()}`, detail: `Thời gian đến ${new Date(item.startAt).toLocaleString('vi-VN')}`, href: '/bookings', createdAt: new Date().toISOString(), read: false })
            const start = new Date(item.startAt).getTime()
            if (item.status === 'CONFIRMED' && start > Date.now() && start - Date.now() <= 15 * 60_000) notices.push({ id: `booking:${item.bookingId}:reminder`, title: 'Sắp đến giờ đặt chỗ', detail: `Còn ít phút đến ${new Date(item.startAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}`, href: '/bookings', createdAt: new Date().toISOString(), read: false })
          }
          for (const item of incidents) {
            const oldStatus = previous.incidents.get(item.incidentId)
            if (oldStatus && oldStatus !== item.status) notices.push({ id: `my-incident:${item.incidentId}:${item.status}`, title: 'Cập nhật báo lỗi', detail: `${item.stationName} · ${statusMessage(item.status)}`, href: '/support/incidents', createdAt: new Date().toISOString(), read: false })
          }
          publish(notices)
        }
        snapshot.current = current
      } catch { /* Notification polling is best effort and must not interrupt the active page. */ }
      finally { if (active) timer = window.setTimeout(poll, 10_000) }
    }
    void poll()
    return () => { active = false; window.clearTimeout(timer) }
  }, [user.role, user.userId])

  useEffect(() => {
    if (!open) return
    const dismiss = (event: MouseEvent) => { if (event.target instanceof Node && !root.current?.contains(event.target)) setOpen(false) }
    const escape = (event: KeyboardEvent) => { if (event.key === 'Escape') setOpen(false) }
    document.addEventListener('mousedown', dismiss)
    document.addEventListener('keydown', escape)
    return () => { document.removeEventListener('mousedown', dismiss); document.removeEventListener('keydown', escape) }
  }, [open])

  const markAllRead = () => setItems(current => current.map(item => ({ ...item, read: true })))
  const openItem = (id: string) => { setItems(current => current.map(item => item.id === id ? { ...item, read: true } : item)); setOpen(false) }

  return <div className="notification-center" ref={root}>
    <button type="button" className="notification-bell" aria-label={unread ? `Thông báo, ${unread} chưa đọc` : 'Thông báo'} aria-expanded={open} aria-controls="notification-popover" onClick={() => setOpen(value => !value)}>
      <Bell size={19} aria-hidden="true" />{unread > 0 && <span className="notification-count">{unread > 9 ? '9+' : unread}</span>}
    </button>
    {open && <section className="notification-popover" id="notification-popover" aria-label="Danh sách thông báo">
      <header className="notification-header"><div><strong>Thông báo</strong><small>{unread ? `${unread} chưa đọc` : 'Đã cập nhật'}</small></div><button type="button" className="notification-read-all" disabled={!unread} onClick={markAllRead}><CheckCheck size={15} />Đọc hết</button></header>
      <div className="notification-list">{items.length ? items.map(item => <Link to={item.href} key={item.id} className={`notification-item${item.read ? '' : ' notification-unread'}`} onClick={() => openItem(item.id)}>
        <span className="notification-item-dot" aria-hidden="true" /><span className="notification-item-copy"><strong>{item.title}</strong><small>{item.detail}</small><time dateTime={item.createdAt}>{relativeTime(item.createdAt)}</time></span>
      </Link>) : <p className="notification-empty">Chưa có thông báo mới.</p>}</div>
    </section>}
  </div>
}
