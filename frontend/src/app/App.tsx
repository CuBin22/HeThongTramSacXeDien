import { incidentLabel, incidentCategoryLabel, connectorLabel } from './vietnamese'
import React, { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate, useParams } from 'react-router-dom'
import { AlertTriangle, ArrowUpRight, BatteryCharging, Bell, Bike, CalendarDays, Camera, ChartNoAxesColumnIncreasing, Clock3, CreditCard, Gauge, Home, ImagePlus, LogOut, MapPin, Pencil, PlugZap, Plus, Shield, Star, UserRound, Users, X, Zap } from 'lucide-react'
import { ApiError, api } from '../api/client'
import type { Booking, ChargeOptions, Incident, Port, Role, User, Vehicle } from '../api/client'
import { ActionButton, ChargeRail, DataTable, EmptyState, Field, FormError, LoadError, Loading, NoticeBox, PageHeader, StatusBadge } from './components'
import type { Notice } from './components'
import { NotificationBell } from './NotificationBell'
import './styles.css'

type AppContextValue = { user: User; notify: (notice: Notice) => void; logout: () => Promise<void> }
const AppContext = createContext<AppContextValue | null>(null)
function useApp() { const value = useContext(AppContext); if (!value) throw new Error('App context is missing'); return value }
function useRequestKeys() {
  const keys = useRef(new Map<string, string>())
  return {
    get: (scope: string) => { const existing = keys.current.get(scope); if (existing) return existing; const next = crypto.randomUUID(); keys.current.set(scope, next); return next },
    reset: (scope: string) => keys.current.delete(scope),
  }
}
const roleNames: Record<Role, string> = { USER: 'Người dùng', ADMIN: 'Quản trị viên' }
const roleHome: Record<Role, string> = { USER: '/', ADMIN: '/admin/reports' }

function messageFor(error: unknown): string {
  if (error instanceof ApiError) {
    if (error.status === 401) return 'Phiên đăng nhập đã hết hạn. Hãy đăng nhập lại.'
    if (error.status === 403) return 'Tài khoản hiện tại không có quyền thực hiện thao tác này.'
    if (error.status === 409) return /[À-ỹ]/.test(error.message) ? error.message : 'Trạng thái vừa thay đổi. Tải lại dữ liệu rồi thử lại.'
    if (error.status === 400) return /[À-ỹ]/.test(error.message) ? error.message : 'Thông tin chưa hợp lệ. Kiểm tra lại các trường vừa nhập.'
    if (error.status === 503) return 'VNPay thử nghiệm chưa được cấu hình. Hãy liên hệ quản trị viên.'
  }
  return 'Không kết nối được hệ thống. Kiểm tra kết nối rồi thử lại.'
}

export function App() {
  const [user, setUser] = useState<User | null>(null)
  const [booting, setBooting] = useState(true)
  const [bootError, setBootError] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const location = useLocation()

  useEffect(() => {
    const section = location.pathname.startsWith('/admin') ? 'Quản trị'
      : location.pathname.startsWith('/incidents') ? 'Sự cố'
        : location.pathname.startsWith('/support/incidents') ? 'Báo lỗi'
        : location.pathname.startsWith('/vehicles') ? 'Xe của tôi'
          : location.pathname.startsWith('/sessions') ? 'Phiên sạc'
            : location.pathname.startsWith('/bookings') ? 'Đặt chỗ'
              : location.pathname.startsWith('/stations') ? 'Trạm sạc' : 'Tổng quan'
    document.title = `${section} · Trạm sạc bệnh viện`
  }, [location.pathname])

  const checkSession = () => {
    setBooting(true); setBootError(false)
    api.me().then(value => setUser(value)).catch(error => {
      if (error instanceof ApiError && [401, 403].includes(error.status)) setUser(null)
      else { setUser(null); setBootError(true) }
    }).finally(() => setBooting(false))
  }
  useEffect(() => { checkSession() }, [])

  const logout = async () => { try { await api.logout() } finally { setNotice(null); setUser(null) } }
  const context = useMemo(() => user ? ({ user, notify: setNotice, logout }) : null, [user])

  if (booting) return <div className="boot-screen"><div className="brand-mark"><Zap size={18} fill="currentColor" /></div><Loading label="Đang kiểm tra phiên đăng nhập" /></div>
  if (bootError) return <div className="boot-screen"><LoadError onRetry={checkSession} /></div>
  if (!user) return <AuthRoutes onAuthenticated={setUser} />
  if (location.pathname === '/login' || location.pathname === '/register') return <Navigate to={roleHome[user.role]} replace />

  return <AppContext.Provider value={context!}>
    <div className="app-frame">
      {user.role === 'USER' && <UserSafetyMonitor />}
      <Sidebar user={user} onLogout={logout} />
      <div className="workspace">
        <Topbar user={user} onLogout={logout} />
        <main className="main-content" id="main-content">
          {notice && <div className="global-notice"><NoticeBox notice={notice} /><button aria-label="Đóng thông báo" onClick={() => setNotice(null)}>×</button></div>}
          <Routes>
            <Route path="/" element={<HomePage />} />
            <Route path="/stations" element={<RoleGate roles={['USER']}><StationDetailPage /></RoleGate>} />
            <Route path="/stations/:id" element={<RoleGate roles={['USER']}><StationDetailPage /></RoleGate>} />
            <Route path="/support/incidents" element={<RoleGate roles={['USER']}><CustomerIncidentsPage /></RoleGate>} />
            <Route path="/vehicles" element={<RoleGate roles={['USER']}><VehiclesPage /></RoleGate>} />
            <Route path="/bookings" element={<RoleGate roles={['USER']}><BookingsPage /></RoleGate>} />
            <Route path="/bookings/new" element={<RoleGate roles={['USER']}><ReservePage /></RoleGate>} />
            <Route path="/sessions" element={<RoleGate roles={['USER']}><SessionsPage /></RoleGate>} />
            <Route path="/sessions/:id" element={<RoleGate roles={['USER']}><SessionPage /></RoleGate>} />
            <Route path="/payment/return" element={<RoleGate roles={['USER']}><PaymentReturnPage /></RoleGate>} />
            <Route path="/incidents" element={<RoleGate roles={['ADMIN']}><IncidentsPage /></RoleGate>} />
            <Route path="/admin/*" element={<RoleGate roles={['ADMIN']}><AdminRoutes /></RoleGate>} />
            <Route path="*" element={<NotFoundPage />} />
          </Routes>
        </main>
        <footer className="app-footer"><span>Trạm sạc bệnh viện · Mô phỏng trạm sạc xe máy điện</span><span>Không điều khiển phần cứng thật</span></footer>
      </div>
    </div>
  </AppContext.Provider>
}

function AuthRoutes({ onAuthenticated }: { onAuthenticated: (user: User) => void }) {
  return <Routes><Route path="/register" element={<AuthPage mode="register" onAuthenticated={onAuthenticated} />} />
    <Route path="*" element={<AuthPage mode="login" onAuthenticated={onAuthenticated} />} /></Routes>
}

function AuthPage({ mode, onAuthenticated }: { mode: 'login' | 'register'; onAuthenticated: (user: User) => void }) {
  const [email, setEmail] = useState(''); const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false); const [busy, setBusy] = useState(false); const [error, setError] = useState('')
  const [fieldError, setFieldError] = useState('')
  const register = mode === 'register'
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setError(''); setFieldError('')
    if (!/^[^\s@]+@[^\s@]+$/.test(email.trim())) { setFieldError('Nhập địa chỉ thư điện tử hợp lệ để tiếp tục.'); return }
    if (password.length < 8) { setFieldError('Mật khẩu cần có ít nhất 8 ký tự.'); return }
    setBusy(true)
    try {
      if (register) { await api.register(email.trim(), password); setError('Tạo tài khoản thành công. Hãy đăng nhập để tiếp tục.'); return }
      onAuthenticated(await api.login(email.trim(), password))
    } catch (reason) {
      setError(register ? messageFor(reason) : 'Địa chỉ thư điện tử hoặc mật khẩu chưa chính xác')
      setPassword('')
    } finally { setBusy(false) }
  }
  return <div className="auth-page">
    <section className="auth-story">
      <div className="auth-brand"><span className="brand-mark"><Zap size={20} fill="currentColor" /></span><span>Trạm sạc<span> bệnh viện</span></span></div>
      <div className="auth-story-copy"><p className="eyebrow">KHUÔN VIÊN BỆNH VIỆN · TRẠM SẠC ĐIỆN</p><h1>Mỗi lượt sạc,<br /><em>đúng cổng</em> đúng giờ.</h1>
        <p>Chọn xe, cổng tương thích và giờ đến. Theo dõi pin cùng trạng thái thiết bị ngay trên một màn hình.</p>
      </div>
    </section>
    <section className="auth-card-wrap"><div className="auth-card">
      <p className="eyebrow">{register ? 'BẮT ĐẦU SỬ DỤNG' : 'CHÀO MỪNG TRỞ LẠI'}</p>
      <h2>{register ? 'Tạo tài khoản' : 'Đăng nhập'}</h2>
      <p className="auth-caption">{register ? 'Tạo tài khoản để đặt chỗ và theo dõi phiên sạc tại bệnh viện.' : 'Đăng nhập để xem lịch đặt chỗ và phiên sạc của bạn.'}</p>
      {error && <div className={register && error.startsWith('Tạo tài khoản') ? 'notice notice-success' : 'form-error'} role="alert">{error}</div>}
      <form noValidate onSubmit={submit} className="form-stack">
        <Field label="Địa chỉ email" type="email" autoComplete="email" value={email} onChange={event => { setEmail(event.target.value); setFieldError(''); setError('') }} error={fieldError && !email.includes('@') ? fieldError : undefined} />
        <div className="secret-field"><Field label="Mật khẩu" type={reveal ? 'text' : 'password'} autoComplete={register ? 'new-password' : 'current-password'} value={password} onChange={event => { setPassword(event.target.value); setFieldError(''); setError('') }} error={fieldError && password.length < 8 ? fieldError : undefined} hint={register ? 'Ít nhất 8 ký tự.' : undefined} />
          <button className="reveal-button" type="button" onClick={() => setReveal(!reveal)} aria-label={reveal ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}>{reveal ? 'Ẩn' : 'Hiện'}</button></div>
        <ActionButton type="submit" busy={busy} className="button-primary button-wide">{register ? 'Tạo tài khoản' : 'Đăng nhập'}<ArrowUpRight size={17} /></ActionButton>
      </form>
      <p className="auth-switch">{register ? 'Đã có tài khoản?' : 'Chưa có tài khoản?'} <Link to={register ? '/login' : '/register'}>{register ? 'Đăng nhập' : 'Tạo tài khoản'}</Link></p>
    </div><span className="auth-note">Thông tin đăng nhập được bảo vệ bằng phiên riêng tư.</span></section>
  </div>
}

function navItems(role: Role) {
  if (role === 'ADMIN') return [
    { to: '/', label: 'Tổng quan', icon: Home }, { to: '/admin/users', label: 'Tài khoản', icon: Users },
    { to: '/admin/stations', label: 'Trụ sạc', icon: PlugZap }, { to: '/admin/tariffs', label: 'Biểu giá', icon: CreditCard },
    { to: '/admin/reports', label: 'Báo cáo', icon: ChartNoAxesColumnIncreasing }, { to: '/incidents', label: 'Sự cố', icon: AlertTriangle },
  ]
  return [
    { to: '/', label: 'Tổng quan', icon: Home }, { to: '/stations', label: 'Trạm sạc', icon: MapPin },
    { to: '/vehicles', label: 'Xe của tôi', icon: Bike }, { to: '/bookings', label: 'Đặt chỗ', icon: CalendarDays }, { to: '/sessions', label: 'Phiên sạc', icon: BatteryCharging }, { to: '/support/incidents', label: 'Báo lỗi', icon: AlertTriangle },
  ]
}

function Sidebar({ user, onLogout }: { user: User; onLogout: () => Promise<void> }) {
  return <aside className="sidebar">
    <Link to="/" className="sidebar-brand"><span className="brand-mark"><Zap size={18} fill="currentColor" /></span><span>Trạm sạc<span> bệnh viện</span></span></Link>
    <div className="sidebar-campus"><span className="campus-pin"><MapPin size={14} /></span><div><strong>Khuôn viên bệnh viện</strong><small>Đang hoạt động</small></div></div>
    <nav className="side-nav" aria-label="Điều hướng chính"><p className="nav-caption">KHÔNG GIAN LÀM VIỆC</p>{navItems(user.role).map(item => {
      const Icon = item.icon; return <NavLink to={item.to} end={item.to === '/'} key={item.to} className={({ isActive }) => `nav-link${isActive ? ' nav-active' : ''}`}><Icon size={18} aria-hidden="true" /><span>{item.label}</span>{item.to === '/incidents' && <span className="nav-pip" aria-label="Sự cố cần theo dõi" />}</NavLink>
    })}</nav>
    <div className="sidebar-bottom">
      <button className="account-button" onClick={onLogout}><span className="account-avatar"><UserRound size={17} /></span><span className="account-copy"><strong>{user.email.split('@')[0]}</strong><small>{roleNames[user.role]}</small></span><LogOut size={16} aria-hidden="true" /></button>
    </div>
  </aside>
}

function Topbar({ user, onLogout }: { user: User; onLogout: () => Promise<void> }) {
  return <header className="topbar"><div className="breadcrumb"><span>KHUÔN VIỆN BỆNH VIỆN</span><span className="breadcrumb-divider">/</span><strong>{roleNames[user.role]}</strong></div>
    <div className="topbar-controls"><NotificationBell user={user} /><div className="topbar-status"><span className="online-dot" />DỊCH VỤ HOẠT ĐỘNG <span className="topbar-divider" /> <button onClick={onLogout}>Đăng xuất</button></div></div></header>
}

function AppShellPage({ children }: { children: React.ReactNode }) { return <>{children}</> }

function RoleGate({ roles, children }: { roles: Role[]; children: React.ReactNode }) {
  const { user } = useApp()
  if (!roles.includes(user.role)) return <ForbiddenPage />
  return <AppShellPage>{children}</AppShellPage>
}

function ForbiddenPage() {
  const { user } = useApp()
  return <section className="forbidden-page"><div className="forbidden-symbol"><Shield size={27} /></div><p className="eyebrow">403 · GIỚI HẠN QUYỀN TRUY CẬP</p>
    <h1>Bạn chưa có quyền vào mục này</h1><p>Vai trò <strong>{roleNames[user.role]}</strong> không thể mở trang này. Quay lại không gian làm việc phù hợp với tài khoản của bạn.</p>
    <Link className="button button-primary" to={roleHome[user.role]}>Về trang chính</Link></section>
}

function NotFoundPage() { return <section className="forbidden-page"><p className="eyebrow">404 · KHÔNG TÌM THẤY</p><h1>Trang này không tồn tại</h1><p>Địa chỉ có thể đã thay đổi hoặc không còn khả dụng.</p><Link className="button button-primary" to="/">Về trang chính</Link></section> }

function HomePage() {
  const { user } = useApp()
  if (user.role === 'ADMIN') return <Navigate to="/admin/reports" replace />
  return <UserHome />
}

function useRead<T>(load: () => Promise<T>, key: string, pollMs = 0) {
  const [data, setData] = useState<T | null>(null); const [error, setError] = useState(false); const [loading, setLoading] = useState(true); const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    let current = true; setLoading(true); setError(false)
    load().then(value => { if (current) setData(value) }).catch(() => { if (current) setError(true) }).finally(() => { if (current) setLoading(false) })
    return () => { current = false }
  }, [key, attempt])
  useEffect(() => {
    if (!pollMs) return
    let current = true
    const timer = window.setInterval(() => { load().then(value => { if (current) setData(value) }).catch(() => {}) }, pollMs)
    return () => { current = false; window.clearInterval(timer) }
  }, [key, pollMs])
  return { data, loading, error, retry: () => setAttempt(value => value + 1), setData }
}

function usePortNames(key: string) {
  const { data: stations } = useRead(api.stations, `${key}-stations`)
  const { data: portGroups } = useRead(
    () => Promise.all((stations ?? []).map(station => api.ports(station.stationId))),
    `${key}-ports-${stations?.map(station => station.stationId).join(',') ?? 'waiting'}`,
  )
  return useMemo(() => {
    const names = new Map<string, string>()
    for (const ports of portGroups ?? []) ports.forEach(port => names.set(port.portId, uppercasePortName(port.displayName)))
    return names
  }, [portGroups])
}

function uppercasePortName(name?: string | null) {
  return (name || 'Trụ sạc').toLocaleUpperCase('vi-VN')
}

function portLabel(portId: string, names: Map<string, string>) {
  return names.get(portId) ?? uppercasePortName()
}

function VehiclesPage() {
  const { notify } = useApp()
  const { data: vehicles, loading, error, retry } = useRead(api.vehicles, 'vehicle-profiles')
  const { data: stations } = useRead(api.stations, 'vehicle-history-stations')
  const [month, setMonth] = useState(campusDateInput().slice(0, 7))
  const [vehicleId, setVehicleId] = useState('')
  const [stationId, setStationId] = useState('')
  const historyKey = `vehicle-history-${month}-${vehicleId}-${stationId}`
  const { data: history, loading: historyLoading, error: historyError, retry: retryHistory } = useRead(() => api.vehicleHistory({ month, vehicleId, stationId }), historyKey)
  const [editing, setEditing] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState('')
  const [draft, setDraft] = useState({ name: '', model: '', licensePlate: '', productionYear: '', batteryCapacityKwh: '', connectorType: 'STANDARD', maxChargingPowerKw: '' })
  const portNames = usePortNames('vehicle-history')
  const resetDraft = () => { setEditing(null); setFormError(''); setDraft({ name: '', model: '', licensePlate: '', productionYear: '', batteryCapacityKwh: '', connectorType: 'STANDARD', maxChargingPowerKw: '' }) }
  const editVehicle = (vehicle: Vehicle) => {
    setEditing(vehicle.vehicleId); setFormError('')
    setDraft({ name: vehicle.name, model: vehicle.model, licensePlate: vehicle.licensePlate ?? '', productionYear: vehicle.productionYear ? String(vehicle.productionYear) : '', batteryCapacityKwh: String(vehicle.batteryCapacityKwh), connectorType: vehicle.connectorType, maxChargingPowerKw: vehicle.maxChargingPowerKw ? String(vehicle.maxChargingPowerKw) : '' })
  }
  const save = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setBusy(true); setFormError('')
    const payload = { name: draft.name.trim(), model: draft.model.trim(), licensePlate: draft.licensePlate.trim() || null, productionYear: draft.productionYear ? Number(draft.productionYear) : null, batteryCapacityKwh: Number(draft.batteryCapacityKwh), connectorType: draft.connectorType, maxChargingPowerKw: draft.maxChargingPowerKw ? Number(draft.maxChargingPowerKw) : null }
    try {
      if (editing) await api.updateVehicle(editing, payload)
      else await api.createVehicle(payload)
      resetDraft(); retry(); notify({ kind: 'success', text: editing ? 'Đã cập nhật phương tiện.' : 'Đã lưu phương tiện.' })
    } catch (reason) { setFormError(messageFor(reason)) } finally { setBusy(false) }
  }
  const makeDefault = async (id: string) => {
    setBusy(true)
    try { await api.makeDefaultVehicle(id); retry(); notify({ kind: 'success', text: 'Đã đặt làm phương tiện mặc định.' }) }
    catch (reason) { notify({ kind: 'error', text: messageFor(reason) }) } finally { setBusy(false) }
  }
  return <>
    <PageHeader eyebrow="PHƯƠNG TIỆN & CHI PHÍ" title="Xe của tôi" detail="Lưu thông tin xe để kiểm tra cổng tương thích và xem lại lịch sử sạc." action={<button className="button button-primary" onClick={() => { resetDraft(); document.getElementById('vehicle-form')?.scrollIntoView({ behavior: 'smooth', block: 'center' }) }}><Plus size={17} />Thêm phương tiện</button>} />
    {loading ? <section className="panel"><Loading label="Đang tải phương tiện" /></section> : error ? <section className="panel"><LoadError onRetry={retry} /></section> : <section className="vehicle-grid" aria-label="Phương tiện đã lưu">
      {vehicles?.map(vehicle => <article className={`vehicle-card${vehicle.isDefault ? ' vehicle-card-default' : ''}`} key={vehicle.vehicleId}>
        <div className="vehicle-card-top"><span className="vehicle-card-icon"><Bike size={22} /></span><div className="vehicle-card-actions">{vehicle.isDefault ? <span className="vehicle-default-badge"><Star size={13} fill="currentColor" />Mặc định</span> : <button className="button button-quiet button-small" disabled={busy} onClick={() => makeDefault(vehicle.vehicleId)}>Đặt mặc định</button>}<button className="button button-quiet button-small" aria-label={`Sửa ${vehicle.name}`} onClick={() => editVehicle(vehicle)}><Pencil size={15} />Sửa</button></div></div>
        <h2>{vehicle.name}</h2><p className="vehicle-model">{vehicle.model}</p>
        <div className="vehicle-facts"><div><span>Biển số</span><strong>{vehicle.licensePlate || 'Chưa cập nhật'}</strong></div><div><span>Năm sản xuất</span><strong>{vehicle.productionYear || 'Chưa cập nhật'}</strong></div><div><span>Dung lượng pin</span><strong>{formatNumber(vehicle.batteryCapacityKwh)} kWh</strong></div><div><span>Loại cổng</span><strong>{connectorLabel(vehicle.connectorType)}</strong></div><div><span>Công suất tối đa</span><strong>{vehicle.maxChargingPowerKw ? `${formatNumber(vehicle.maxChargingPowerKw)} kW` : 'Chưa cập nhật'}</strong></div></div>
      </article>)}
      {!vehicles?.length && <section className="panel vehicle-empty"><span className="vehicle-card-icon"><Bike size={24} /></span><h2>Chưa có phương tiện</h2><p>Lưu xe đầu tiên để đặt chỗ đúng loại cổng và gắn lịch sử sạc với xe.</p><button className="button button-primary" onClick={() => document.getElementById('vehicle-form')?.scrollIntoView({ behavior: 'smooth', block: 'center' })}><Plus size={16} />Thêm phương tiện</button></section>}
    </section>}

    <section className="panel vehicle-form-panel" id="vehicle-form"><div className="panel-heading"><div><p className="eyebrow">HỒ SƠ PHƯƠNG TIỆN</p><h2>{editing ? 'Cập nhật thông tin xe' : 'Thêm phương tiện'}</h2></div>{editing && <button className="button button-quiet button-small" onClick={resetDraft}>Hủy chỉnh sửa</button>}</div>
      <form className="vehicle-form" onSubmit={save}>
        <Field label="Tên phương tiện" value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} placeholder="Ví dụ: Xe đi học" required />
        <Field label="Mẫu xe" value={draft.model} onChange={e => setDraft({ ...draft, model: e.target.value })} placeholder="Ví dụ: VinFast Evo200" required />
        <Field label="Biển số xe" value={draft.licensePlate} onChange={e => setDraft({ ...draft, licensePlate: e.target.value })} placeholder="Không bắt buộc" />
        <Field label="Năm sản xuất" type="number" min="1900" max={new Date().getFullYear() + 1} value={draft.productionYear} onChange={e => setDraft({ ...draft, productionYear: e.target.value })} placeholder="Không bắt buộc" />
        <Field label="Dung lượng pin (kWh)" type="number" min="0.1" step="0.1" value={draft.batteryCapacityKwh} onChange={e => setDraft({ ...draft, batteryCapacityKwh: e.target.value })} required />
        <label className="field"><span className="field-label">Loại đầu nối</span><select value={draft.connectorType} onChange={e => setDraft({ ...draft, connectorType: e.target.value })}><option value="STANDARD">Phổ thông</option><option value="TYPE_2">Type 2</option><option value="GB_T">GB/T</option><option value="CCS2">CCS2</option><option value="CHADEMO">CHAdeMO</option></select></label>
        <Field label="Công suất sạc tối đa (kW)" type="number" min="0.1" step="0.1" value={draft.maxChargingPowerKw} onChange={e => setDraft({ ...draft, maxChargingPowerKw: e.target.value })} placeholder="Không bắt buộc" />
        <div className="vehicle-form-note">Thông tin công suất và năm sản xuất chưa có ở hồ sơ cũ sẽ được để trống cho đến khi bạn cập nhật.</div>
        <FormError message={formError} /><div className="form-actions"><ActionButton type="submit" busy={busy} className="button-primary">{editing ? 'Lưu thay đổi' : 'Lưu phương tiện'}<ArrowUpRight size={16} /></ActionButton></div>
      </form>
    </section>

    <section className="panel panel-spacious vehicle-history-panel"><div className="panel-heading"><div><p className="eyebrow">PHIÊN ĐÃ HOÀN TẤT</p><h2>Lịch sử sạc</h2><p className="panel-subtitle">Chi phí đã quyết toán và khoản tiền thực trả trong kỳ được chọn.</p></div></div>
      <div className="vehicle-history-filters"><Field label="Tháng" type="month" value={month} onChange={e => setMonth(e.target.value)} /><label className="field"><span className="field-label">Phương tiện</span><select value={vehicleId} onChange={e => setVehicleId(e.target.value)}><option value="">Tất cả phương tiện</option>{vehicles?.map(vehicle => <option key={vehicle.vehicleId} value={vehicle.vehicleId}>{vehicle.name}</option>)}</select></label><label className="field"><span className="field-label">Trạm sạc</span><select value={stationId} onChange={e => setStationId(e.target.value)}><option value="">Tất cả trạm</option>{stations?.map(station => <option key={station.stationId} value={station.stationId}>{station.name}</option>)}</select></label></div>
      {historyLoading ? <Loading label="Đang tổng hợp lịch sử sạc" /> : historyError ? <LoadError onRetry={retryHistory} /> : <>
        <div className="vehicle-history-metrics"><div><span>Phiên sạc</span><strong>{history?.totalSessions ?? 0}</strong></div><div><span>Điện năng</span><strong>{formatNumber(history?.totalEnergyKwh ?? 0)} <small>kWh</small></strong></div><div><span>Tổng chi phí</span><strong>{formatVnd(history?.totalCostVnd ?? 0)}</strong></div><div><span>Đã thanh toán</span><strong>{formatVnd(history?.totalPaidVnd ?? 0)}</strong></div></div>
        {!history?.sessions.length ? <EmptyState title="Chưa có phiên sạc trong kỳ này" detail="Thử chọn tháng khác hoặc bắt đầu một phiên sạc gắn với phương tiện đã lưu." action={<Link className="button button-secondary" to="/bookings">Xem lịch đặt chỗ</Link>} /> : <div className="vehicle-history-list">{history.sessions.map(item => <article className="vehicle-history-row" key={item.sessionId}><span className="session-icon"><BatteryCharging size={18} /></span><div className="vehicle-history-copy"><strong>{item.stationName} · {portLabel(item.portId, portNames)}</strong><span>{item.vehicleName} · {formatDateTime(item.startedAt)}</span></div><div className="vehicle-history-energy"><strong>{formatNumber(item.energyKwh)} kWh</strong><span>Chi phí {formatVnd(item.totalCostVnd)}</span></div><div className="vehicle-history-paid"><strong>{formatVnd(item.amountPaidVnd)}</strong><span>Đã thanh toán</span></div><Link to={`/sessions/${item.sessionId}`} className="button button-secondary button-small">Chi tiết</Link></article>)}</div>}
      </>}
    </section>
  </>
}

const reportCategories = ['START_CHARGE_FAILED','GUN_STUCK','INCORRECT_BILLING','DISPLAY_FAILURE','STOP_CHARGE_FAILED','OTHER'] as const

function CustomerIncidentsPage() {
  const location = useLocation()
  const { notify } = useApp()
  const requestKeys = useRequestKeys()
  const { data: stations, loading: stationsLoading, error: stationsError, retry: retryStations } = useRead(api.stations, 'report-stations')
  const { data: allPorts, loading: portsLoading } = useRead(() => Promise.all((stations ?? []).map(station => api.ports(station.stationId))), `report-ports-${stations?.map(item => item.stationId).join(',') ?? 'waiting'}`)
  const { data: sessions } = useRead(api.sessions, 'report-sessions')
  const { data: reports, loading: reportsLoading, error: reportsError, retry: retryReports } = useRead(api.myIncidents, 'my-incident-reports', 5000)
  const query = new URLSearchParams(location.search)
  const [stationId, setStationId] = useState(query.get('stationId') ?? '')
  const [portId, setPortId] = useState(query.get('portId') ?? '')
  const [sessionId, setSessionId] = useState(query.get('sessionId') ?? '')
  const [category, setCategory] = useState<(typeof reportCategories)[number]>('START_CHARGE_FAILED')
  const [description, setDescription] = useState('')
  const [files, setFiles] = useState<File[]>([])
  const [previews, setPreviews] = useState<string[]>([])
  const [formError, setFormError] = useState('')
  const [busy, setBusy] = useState(false)
  const fileInput = useRef<HTMLInputElement>(null)
  const portGroups = allPorts ?? []
  const ports = portGroups.flat()
  const portMap = new Map(ports.map(port => [port.portId, port]))
  const portNames = useMemo(() => new Map(portGroups.flatMap(group => group.map(port => [port.portId,uppercasePortName(port.displayName)] as const))),[allPorts])
  const selectedPort = portMap.get(portId)
  const stationPorts = ports.filter(port => port.stationId === stationId)
  const relatedSessions = (sessions ?? []).filter(session => session.portId === portId)
  useEffect(() => {
    if (!stations?.length) return
    const requestedId = query.get('stationId')
    const defaultId = stations.find(station => station.stationId === requestedId)?.stationId
      ?? stations.find(station => station.active)?.stationId
      ?? stations[0].stationId
    if (!stationId || !stations.some(station => station.stationId === stationId)) setStationId(defaultId)
  }, [stations, stationId, location.search])
  useEffect(() => {
    if (selectedPort && selectedPort.stationId !== stationId) setStationId(selectedPort.stationId)
  }, [selectedPort?.portId, selectedPort?.stationId, stationId])
  useEffect(() => {
    const urls=files.map(file=>URL.createObjectURL(file)); setPreviews(urls)
    return () => urls.forEach(url=>URL.revokeObjectURL(url))
  }, [files])
  const onFiles = (event: React.ChangeEvent<HTMLInputElement>) => {
    const picked=Array.from(event.target.files ?? [])
    if(picked.length>3) { setFormError('Bạn chỉ có thể đính kèm tối đa 3 ảnh.'); event.target.value=''; return }
    const invalid=picked.find(file=>!['image/jpeg','image/png','image/webp'].includes(file.type) || file.size>5*1024*1024)
    if(invalid) { setFormError(invalid.size>5*1024*1024?'Mỗi ảnh không được vượt quá 5 MB.':'Chỉ nhận ảnh JPEG, PNG hoặc WebP.'); event.target.value=''; return }
    setFormError(''); setFiles(picked)
  }
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setFormError('')
    if(!portId || !selectedPort) { setFormError('Chọn cổng cần hỗ trợ.'); return }
    if(category==='OTHER' && !description.trim()) { setFormError('Mô tả lỗi khác để nhân viên hiểu vấn đề bạn gặp.'); return }
    const form=new FormData(); form.append('portId',portId); form.append('category',category)
    if(sessionId) form.append('sessionId',sessionId)
    if(description.trim()) form.append('description',description.trim())
    files.forEach(file=>form.append('images',file,file.name))
    setBusy(true)
    try {
      await api.reportIncident(form,requestKeys.get(`customer-incident:${portId}:${category}`))
      requestKeys.reset(`customer-incident:${portId}:${category}`); setDescription(''); setFiles([]); setSessionId(''); if(fileInput.current) fileInput.current.value=''
      retryReports(); notify({kind:'success',text:'Đã gửi báo lỗi. Admin sẽ tiếp nhận và phân công nhân viên xử lý.'})
    } catch(reason) { setFormError(messageFor(reason)) } finally { setBusy(false) }
  }
  const portNamesForIncident = (id:string) => portNames.get(id) ?? uppercasePortName()
  return <>
    <PageHeader eyebrow="HỖ TRỢ" title="Báo lỗi" detail="Gửi thông tin để nhân viên kiểm tra đúng vị trí và cổng. Báo cáo này không tự điều khiển thiết bị." />
    {stationsError && <section className="panel"><LoadError onRetry={retryStations} /></section>}
    {!stationsError && <section className="customer-report-layout">
      <form className="panel customer-report-form" onSubmit={submit}>
        <div className="report-context-card"><span className="report-context-icon"><PlugZap size={21} /></span><div><strong>{selectedPort ? `${stations?.find(s=>s.stationId===stationId)?.name ?? 'Trạm sạc'} · ${portNamesForIncident(portId)}` : 'Chọn vị trí cần hỗ trợ'}</strong><small>{sessionId ? `Phiên ${sessionId.slice(0,8).toUpperCase()}` : 'Bạn có thể liên kết với một phiên sạc cụ thể'}</small></div></div>
        <div className="report-select-grid">
          <label className="field"><span className="field-label">Trụ sạc</span><select required value={portId} disabled={!stationId || portsLoading} onChange={e=>{setPortId(e.target.value);setSessionId('')}}><option value="">Chọn trụ sạc</option>{stationPorts.map(port=><option key={port.portId} value={port.portId}>{portNamesForIncident(port.portId)} · {connectorLabel(port.connectorType ?? 'STANDARD')}</option>)}</select></label>
          <label className="field report-session-field"><span className="field-label">Phiên liên quan <small>(không bắt buộc)</small></span><select value={sessionId} disabled={!portId || relatedSessions.length===0} onChange={e=>setSessionId(e.target.value)}><option value="">Không chọn phiên</option>{relatedSessions.map(session=><option key={session.sessionId} value={session.sessionId}>{formatDateTime(session.startedAt)} · {statusLabel(session.status)}</option>)}</select></label>
        </div>
        <fieldset className="report-category-fieldset"><legend>Chọn nhóm lỗi</legend><div className="report-category-list">{reportCategories.map(value=><label key={value} className={`report-category-option${category===value?' report-category-selected':''}`}><input type="radio" name="report-category" value={value} checked={category===value} onChange={()=>setCategory(value)} /><span>{incidentCategoryLabel(value)}</span><i aria-hidden="true" /></label>)}</div></fieldset>
        <label className="field"><span className="field-label">Mô tả thêm <small>{category==='OTHER'?'(bắt buộc)':'(không bắt buộc)'}</small></span><textarea className="report-description" maxLength={1500} value={description} onChange={e=>setDescription(e.target.value)} placeholder="Mô tả điều đã xảy ra để nhân viên hỗ trợ nhanh hơn…" /><span className="report-char-count">{description.length}/1.500 ký tự</span></label>
        <div className="report-upload-section"><div><strong>Ảnh hiện trường</strong><p>JPEG, PNG hoặc WebP · tối đa 3 ảnh, 5 MB mỗi ảnh</p></div><div className="report-upload-grid">{previews.map((src,index)=><div className="report-image-preview" key={`${files[index]?.name}-${index}`}><img src={src} alt={`Ảnh đính kèm ${index+1}`} /><button type="button" aria-label={`Xóa ảnh ${index+1}`} onClick={()=>setFiles(current=>current.filter((_,i)=>i!==index))}><X size={15} /></button></div>)}{files.length<3&&<button type="button" className="report-add-image" onClick={()=>fileInput.current?.click()}><ImagePlus size={21} /><span>Thêm ảnh</span></button>}</div><input ref={fileInput} className="visually-hidden" type="file" accept="image/jpeg,image/png,image/webp" multiple onChange={onFiles} /></div>
        <NoticeBox notice={{kind:'info',text:'Báo lỗi chỉ gửi thông tin hỗ trợ. Hệ thống không tự dừng phiên hoặc khóa cổng; sự cố an toàn do thiết bị phát hiện vẫn xử lý theo quy trình riêng.'}} />
        {formError&&<FormError message={formError} />}
        <div className="form-actions"><ActionButton type="submit" busy={busy} disabled={stationsLoading||portsLoading||!stations?.length} className="button-primary"><AlertTriangle size={16} />Gửi báo lỗi</ActionButton></div>
      </form>
      <aside className="panel customer-report-help"><span className="report-help-icon"><Camera size={20} /></span><p className="eyebrow">NHÂN VIÊN SẼ NHẬN</p><h2>Thông tin xử lý đầy đủ</h2><ul><li>Vị trí và cổng sạc đã chọn</li><li>Mã phiên nếu bạn liên kết phiên</li><li>Nhóm lỗi, mô tả và ảnh đính kèm</li></ul><p>Bạn theo dõi trạng thái tiếp nhận ở danh sách báo cáo bên dưới.</p></aside>
    </section>}
    <section className="panel panel-spacious customer-report-history"><div className="panel-heading"><div><p className="eyebrow">THEO DÕI HỖ TRỢ</p><h2>Báo cáo của bạn</h2></div><span className="data-count">{reports?.length ?? 0} báo cáo</span></div>
      {reportsLoading?<Loading label="Đang tải báo cáo đã gửi" />:reportsError?<LoadError onRetry={retryReports} />:!reports?.length?<EmptyState title="Bạn chưa gửi báo lỗi" detail="Khi cần hỗ trợ tại trạm, hãy gửi báo cáo ngay trên trang này." />:<div className="customer-report-list">{reports.map(item=><article className="customer-report-row" key={item.incidentId}><span className="report-context-icon"><AlertTriangle size={18} /></span><div className="customer-report-row-copy"><div><strong>{incidentCategoryLabel(item.category)}</strong><StatusBadge value={item.status} /></div><p>{item.stationName} · Cổng {portNamesForIncident(item.portId)} · {formatDateTime(item.reportedAt)}</p>{item.sessionId&&<small>Phiên {item.sessionId.slice(0,8).toUpperCase()}</small>}{item.description!==incidentCategoryLabel(item.category)&&<p className="customer-report-details">{item.description}</p>}{item.attachmentIds.length>0&&<div className="report-saved-images">{item.attachmentIds.map(fileId=><a key={fileId} href={api.incidentAttachmentUrl(item.incidentId,fileId,true)} target="_blank" rel="noreferrer"><img src={api.incidentAttachmentUrl(item.incidentId,fileId,true)} alt="Ảnh đính kèm trong báo cáo" /></a>)}</div>}</div><StatusBadge value={item.status} /></article>)}</div>}
    </section>
  </>
}

function UserSafetyMonitor() {
  const { data: sessions } = useRead(api.sessions, 'user-safety-monitor', 2000)
  const { notify } = useApp()
  const seenOverheats = useRef<Set<string> | null>(null)
  useEffect(() => {
    if (!sessions) return
    const faulted = sessions.filter(item => (item.deviceStatus === 'FAULTED' && item.commandReason?.includes('OVERHEAT')) || (item.deviceStatus === 'OFFLINE' && item.commandReason?.includes('HEARTBEAT_TIMEOUT')))
    const previous = seenOverheats.current
    const fresh = previous ? faulted.find(item => !previous.has(item.sessionId)) : faulted[0]
    seenOverheats.current = new Set(faulted.map(item => item.sessionId))
    if (fresh?.deviceStatus === 'OFFLINE') notify({ kind: 'error', text: `MẤT KẾT NỐI: trụ ${uppercasePortName(fresh.portName ?? 'đang sạc')} không phản hồi. Đã yêu cầu dừng sạc và báo nhân viên. Đang chờ thiết bị kết nối lại để xác nhận dừng.` })
    else if (fresh) notify({ kind: 'error', text: `CẢNH BÁO QUÁ NHIỆT: trụ ${uppercasePortName(fresh.portName ?? 'đang sạc')} đã gửi lệnh ngắt sạc. Admin đã được thông báo.` })
  }, [sessions, notify])
  return null
}

function UserHome() {
  const { data: bookings } = useRead(api.bookings, 'home-bookings')
  const { data: sessions } = useRead(api.sessions, 'home-sessions')
  const active = sessions?.find(session => session.status !== 'COMPLETED')
  return <>
    <PageHeader eyebrow="KHUÔN VIÊN BỆNH VIỆN · TRẠM SẠC" title="Sạc thuận tiện, bắt đầu từ đây" detail="Đặt khung giờ phù hợp. Hệ thống sẽ tự tìm cổng sạc còn trống cho bạn." action={<Link className="button button-primary" to="/bookings/new"><CalendarDays size={17} />Đặt chỗ sạc</Link>} />
    <div className="overview-grid"><div className="overview-main">
      {active ? <Link to={`/sessions/${active.sessionId}`} className="active-session-card"><div className="active-session-head"><span className="live-tag"><span className="online-dot" />PHIÊN ĐANG HOẠT ĐỘNG</span><span className="session-arrow"><ArrowUpRight size={18} /></span></div><div className="active-session-body"><div><strong>{active.batteryPercent}%</strong><small>mức pin hiện tại</small></div><ChargeRail value={active.batteryPercent} /></div><div className="active-session-foot"><StatusBadge value={active.status} /><span>{active.portName}</span></div></Link>
        : <section className="feature-card"><div className="feature-copy"><p className="eyebrow">CỔNG SẠC ĐƯỢC PHÂN BỔ TỰ ĐỘNG</p><h2>Không cần chọn cổng.<br />Chỉ cần chọn thời gian.</h2><p>Đặt lịch tại một trạm. Hệ thống tìm cổng phù hợp và giữ chỗ cho bạn.</p><Link to="/bookings/new" className="button button-primary">Chọn khung giờ <ArrowUpRight size={17} /></Link></div><div className="feature-art" aria-hidden="true"><div className="art-orbit orbit-one" /><div className="art-orbit orbit-two" /><div className="art-battery"><BatteryCharging size={35} /><span>SẴN SÀNG</span></div><div className="art-caption">NĂNG LƯỢNG<br />BỆNH VIỆN</div></div></section>}
    </div><aside className="overview-side">
      <section className="side-summary"><div className="summary-icon"><CalendarDays size={18} /></div><span className="summary-number">{bookings?.filter(item => ['CONFIRMED', 'IN_USE'].includes(item.status)).length ?? '—'}</span><span className="summary-label">lượt đặt chỗ sắp tới</span><Link to="/bookings">Xem lịch của bạn <ArrowUpRight size={14} /></Link></section>
      <div className="campus-stamp"><span>DỊCH VỤ SẠC</span><strong>XE MÁY ĐIỆN</strong><small>THUẬN TIỆN NGAY TRONG KHUÔN VIÊN BỆNH VIỆN</small></div>
    </aside></div>
  </>
}

function StationDetailPage() {
  const { id } = useParams()
  const { data: stations } = useRead(api.stations, 'station-detail-stations')
  const station = id ? stations?.find(item => item.stationId === id) : stations?.[0]
  const stationId = station?.stationId ?? id ?? ''
  const { data: ports, loading, error, retry } = useRead(() => stationId ? api.ports(stationId) : Promise.resolve([]), `station-detail-ports-${stationId}`, 5000)
  const { data: tariffs, loading: tariffLoading } = useRead(() => stationId ? api.currentTariff(stationId) : Promise.resolve([]), `station-detail-tariff-${stationId}`)
  const available = station?.active ? ports?.filter(port => port.status === 'AVAILABLE').length ?? 0 : 0
  const charging = ports?.filter(port => port.status === 'CHARGING').length ?? 0
  const reserved = ports?.filter(port => port.status === 'RESERVED').length ?? 0
  if (!stations) return <Loading label="Đang tải thông tin trạm" />
  if (!station) return <NotFoundPage />
  const tariff = tariffs?.[0]
  return <>
    <PageHeader eyebrow="TRỤ SẠC TRONG BỆNH VIỆN" title={station.name} detail={`${station.active ? 'Đang hoạt động' : 'Tạm đóng'} · ${available} trống · ${charging} đang sạc · ${reserved} đã đặt · ${ports?.length ?? 0} trụ`} action={station.active ? <Link className="button button-primary" to={`/bookings/new?stationId=${encodeURIComponent(stationId)}`}><CalendarDays size={16} />Đặt chỗ tại trạm</Link> : <button className="button button-secondary" disabled><CalendarDays size={16} />Trạm đang tạm đóng</button>} />
    <div className="station-detail-layout">
      <section className="panel panel-spacious station-detail-ports"><div className="panel-heading"><div><p className="eyebrow">TRẠNG THÁI CẬP NHẬT MỖI 5 GIÂY</p><h2>Các trụ sạc</h2></div><span className="data-count">{ports?.length ?? 0} trụ</span></div>
        <div className="station-status-legend"><span><i className="legend-free" />Trống</span><span><i className="legend-charging" />Đang sạc</span><span><i className="legend-unavailable" />Đã đặt trước</span><span><i className="legend-unavailable" />Tạm khóa / bảo trì</span><span><i className="legend-unavailable" />Sự cố / mất kết nối</span></div>
        {loading ? <Loading label="Đang tải trạng thái trụ sạc" /> : error ? <LoadError onRetry={retry} /> : !ports?.length ? <EmptyState title="Trạm chưa có trụ sạc" detail="Vui lòng quay lại sau." /> : <div className="station-detail-port-list">{ports.map(port => {
          const state = !station.active ? 'DISABLED' : port.deviceStatus === 'OFFLINE' ? 'OFFLINE' : port.status
          return <article className="station-detail-port" key={port.portId}><span className={`station-port-icon ${state === 'AVAILABLE' ? 'port-icon-ready' : state === 'CHARGING' ? 'port-icon-charging' : ''}`}><PlugZap size={19} /></span><div className="station-port-name"><strong>{uppercasePortName(port.displayName)}</strong><small>{port.chargingMode ?? 'AC'} · {connectorLabel(port.connectorType ?? station.connectorType)} · tối đa {formatNumber(port.maxPowerKw ?? 3)} kW</small>{port.bookingStartAt && port.bookingEndAt && <small>Lượt hiện tại: {formatDateTime(port.bookingStartAt)} – {formatTime(port.bookingEndAt)}</small>}{port.nextBookingStartAt && <small className="port-next-booking">Đặt trước: {formatDateTime(port.nextBookingStartAt)} – {formatTime(port.nextBookingEndAt ?? port.nextBookingStartAt)}</small>}{port.incidentDescription && <small className="port-incident-detail">{incidentLabel(port.incidentDescription)}</small>}<Link className="station-port-report-link" to={`/support/incidents?stationId=${encodeURIComponent(stationId)}&portId=${encodeURIComponent(port.portId)}`}>Báo lỗi trụ</Link></div><div className="station-port-meta"><StatusBadge value={state} />{port.lastSeenAt && <small>Tín hiệu {formatTime(port.lastSeenAt)}</small>}</div></article>
        })}</div>}
        <p className="station-live-note"><span className="online-dot" />Dữ liệu trạng thái, sự cố và lịch đặt tự làm mới mỗi 5 giây.</p>
      </section>
      <aside className="station-detail-side">
        <section className="panel station-price-panel"><p className="eyebrow">GIÁ SẠC HIỆN HÀNH</p><h2>Biểu giá</h2>{tariffLoading ? <Loading label="Đang tải biểu giá" /> : tariff ? <><strong className="station-price-value">{formatVnd(tariff.pricePerKwh)}<small>/ kWh hiện tại</small></strong><p>Hiệu lực từ {formatDateTime(tariff.effectiveAt)}</p>{tariff.dayPricePerKwh != null && tariff.eveningPricePerKwh != null ? <><div className="station-price-period"><strong>{timeLabel(tariff.dayStart)}–{timeLabel(tariff.dayEnd)}</strong><span>{formatVnd(tariff.dayPricePerKwh)} / kWh</span></div><div className="station-price-period"><strong>{timeLabel(tariff.eveningStart)}–{timeLabel(tariff.eveningEnd)}</strong><span>{formatVnd(tariff.eveningPricePerKwh)} / kWh</span></div><small className="form-hint">Ngoài hai khung giờ trên, áp dụng giá buổi tối.</small></> : <div className="station-price-period"><strong>Đơn giá</strong><span>{formatVnd(tariff.pricePerKwh)} / kWh</span></div>}<div className="station-price-period"><strong>Phí chiếm chỗ</strong><span>{tariff.occupancyGraceMinutes} phút miễn phí, sau đó {formatVnd(tariff.occupancyFeePerMinute)} / phút</span></div><small className="form-hint">Hiện chưa tự động thu phí chiếm chỗ vì thiết bị chưa gửi tín hiệu xe rời cổng.</small></> : <p>Chưa có biểu giá áp dụng cho trạm này.</p>}</section>
        <section className="panel station-rules-panel"><span className="station-rule-icon"><Shield size={18} /></span><h3>Quy định đặt chỗ</h3><p>Bạn có thể chọn giờ đến tùy ý. Mỗi lượt giữ cổng trong 4 giờ.</p><p>Bạn có 15 phút từ giờ hẹn để bắt đầu sạc. Quá thời hạn, lượt giữ chỗ hết hạn và cổng được giải phóng.</p><p>Phí giữ chỗ hiện chưa áp dụng. Giá sạc tính theo điện năng và biểu giá khi bắt đầu phiên.</p></section>
      </aside>
    </div>
  </>
}

function ReservePage() {
  const { data: stations, loading, error, retry } = useRead(api.stations, 'reserve-stations')
  const location = useLocation()
  const { notify } = useApp()
  const requestKeys = useRequestKeys()
  const { data: vehicles, loading: vehiclesLoading, retry: reloadVehicles } = useRead(api.vehicles, 'reserve-vehicles')
  const [stationId, setStationId] = useState(() => new URLSearchParams(location.search).get('stationId') ?? '')
  const [vehicleId, setVehicleId] = useState(''); const [date, setDate] = useState(campusDateInput())
  const [slot, setSlot] = useState(''); const [portId, setPortId] = useState('')
  const [addVehicle, setAddVehicle] = useState(false); const [savingVehicle, setSavingVehicle] = useState(false); const [vehicleName, setVehicleName] = useState('Xe máy của tôi')
  const [vehicleModel, setVehicleModel] = useState('Xe máy điện'); const [vehicleConnector, setVehicleConnector] = useState('STANDARD'); const [vehicleCapacity, setVehicleCapacity] = useState('3')
  const [busy, setBusy] = useState(false); const [formError, setFormError] = useState(''); const [created, setCreated] = useState<Booking | null>(null)
  useEffect(() => {
    if (!stations?.length) return
    const requestedId = new URLSearchParams(location.search).get('stationId')
    const defaultId = stations.find(item => item.active && item.stationId === requestedId)?.stationId
      ?? stations.find(item => item.active)?.stationId
      ?? stations[0].stationId
    if (!stations.some(item => item.active && item.stationId === stationId)) {
      setStationId(defaultId)
      setPortId('')
      setSlot('')
    }
  }, [stations, location.search, stationId])
  const station = stations?.find(item => item.stationId === stationId)
  const selectedVehicle = vehicles?.find(item => item.vehicleId === vehicleId)
  const startAt = slot && date ? `${date}T${slot}` : ''
  const startIso = startAt ? campusInstant(startAt) : ''
  const endIso = startIso ? new Date(new Date(startIso).getTime() + 4 * 60 * 60 * 1000).toISOString() : ''
  const startInFuture = Boolean(startIso && new Date(startIso).getTime() > Date.now())
  const { data: stationPorts } = useRead(() => stationId ? api.ports(stationId) : Promise.resolve([]), `reserve-station-ports-${stationId}`)
  const availabilityKey = `reserve-availability-${stationId}-${vehicleId}-${startIso}-${endIso}`
  const { data: availablePorts, loading: availabilityLoading, error: availabilityError, retry: retryAvailability } = useRead(
    () => stationId && vehicleId && startIso && endIso && startInFuture ? api.availablePorts(stationId, vehicleId, startIso, endIso) : Promise.resolve([]), availabilityKey, 5000,
  )
  const portNames = useMemo(() => new Map((stationPorts ?? []).map(port => [port.portId, uppercasePortName(port.displayName)])), [stationPorts])
  const chosenPort = availablePorts?.find(port => port.portId === portId)
  const connectorTypes = [...new Set((stationPorts ?? []).map(port => port.connectorType).filter((value): value is string => Boolean(value)))]
  useEffect(() => { if (connectorTypes.length && !connectorTypes.includes(vehicleConnector)) setVehicleConnector(connectorTypes[0]) }, [connectorTypes.join('|'), vehicleConnector])
  useEffect(() => { if (portId && availablePorts && !availablePorts.some(port => port.portId === portId)) setPortId('') }, [availablePorts, portId])
  const resetReservationKey = () => { requestKeys.reset('reserve'); setFormError('') }
  const saveVehicle = async () => {
    if (!vehicleName.trim() || !vehicleModel.trim() || !Number.isFinite(Number(vehicleCapacity)) || Number(vehicleCapacity) <= 0) { setFormError('Nhập đầy đủ thông tin xe và dung lượng pin hợp lệ.'); return }
    setSavingVehicle(true)
    try {
      const value = await api.createVehicle({ name: vehicleName, model: vehicleModel, connectorType: vehicleConnector, batteryCapacityKwh: Number(vehicleCapacity) })
      reloadVehicles(); setVehicleId(value.vehicleId); setAddVehicle(false); setPortId(''); resetReservationKey()
    } catch (reason) { setFormError(messageFor(reason)) } finally { setSavingVehicle(false) }
  }
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setFormError('')
    if (!stationId || !vehicleId || !startAt || !portId) { setFormError('Chọn phương tiện, trạm, giờ đến và cổng còn trống.'); return }
    if (!startInFuture) { setFormError('Giờ đến phải nằm trong tương lai.'); return }
    if (!chosenPort) { setFormError('Cổng vừa thay đổi trạng thái. Tải lại danh sách rồi chọn cổng khác.'); retryAvailability(); return }
    setBusy(true)
    try {
      const value = await api.reserve({ stationId, portId, vehicleId, startAt: startIso, endAt: endIso }, requestKeys.get('reserve'))
      requestKeys.reset('reserve'); setCreated(value); notify({ kind: 'success', text: `Đã giữ cổng ${portLabel(value.portId, portNames)} trong 4 giờ.` })
    } catch (reason) {
      setFormError(reason instanceof ApiError && reason.status === 409 ? 'Cổng vừa được người khác đặt hoặc không còn sẵn sàng. Chọn cổng khác hoặc thử lại.' : messageFor(reason)); retryAvailability()
    } finally { setBusy(false) }
  }
  return <><PageHeader eyebrow="ĐẶT TRƯỚC KHI ĐẾN" title="Đặt chỗ sạc" detail="Chọn xe, cổng phù hợp và giờ đến theo nhu cầu. Mỗi lượt giữ cổng trong 4 giờ." />
    {created ? <section className="confirmation-card"><div className="confirmation-icon confirmation-good"><CalendarDays size={22} /></div><p className="eyebrow">ĐÃ GIỮ CHỖ</p><h2>{portLabel(created.portId, portNames)} đã được giữ cho bạn</h2><p>{stations?.find(item => item.stationId === created.stationId)?.name ?? 'Trạm sạc'} · {formatDateTime(created.startAt)} – {formatDateTime(created.endAt)}</p><p>Đến trước <strong>{formatTime(new Date(new Date(created.startAt).getTime() + 15 * 60 * 1000).toISOString())}</strong> để bắt đầu sạc. Phí đặt chỗ: <strong>Chưa áp dụng</strong>.</p><div className="confirmation-actions"><Link className="button button-primary" to="/bookings">Xem lịch đặt chỗ</Link><button className="button button-secondary" onClick={() => { setCreated(null); setSlot(''); setPortId(''); resetReservationKey() }}>Đặt thêm khung giờ</button></div></section>
      : <section className="form-panel reserve-panel"><div className="form-panel-intro"><span className="form-icon"><CalendarDays size={19} /></span><div><h2>Chọn thời gian đến</h2><p>Giờ hiển thị theo múi giờ Việt Nam (UTC+7).</p></div></div>
        {loading ? <Loading label="Đang tải trạm sạc" /> : error ? <LoadError onRetry={retry} /> : <form noValidate className="form-stack" onSubmit={submit}>
          <div className="field-row reserve-stationless-row"><label className="field"><span className="field-label">Phương tiện</span><select value={vehicleId} onChange={event => { setVehicleId(event.target.value); setPortId(''); resetReservationKey() }} aria-label="Phương tiện"><option value="">Chọn xe đã lưu</option>{vehicles?.map(item => <option key={item.vehicleId} value={item.vehicleId}>{item.name} · {connectorLabel(item.connectorType)}</option>)}</select></label></div>
          {vehiclesLoading ? <Loading label="Đang tải phương tiện đã lưu" /> : !stationId && !vehicles?.length ? <p className="reserve-empty">Đang tải thông tin khuôn viên bệnh viện…</p> : (!vehicles?.length || addVehicle) ? <div className="reserve-vehicle-create"><div className="panel-heading"><div><p className="eyebrow">PHƯƠNG TIỆN</p><h3>{vehicles?.length ? 'Thêm xe mới' : 'Lưu xe để kiểm tra tương thích'}</h3></div>{vehicles?.length ? <button type="button" className="button button-quiet button-small" onClick={() => setAddVehicle(false)}>Đóng</button> : null}</div>
            <div className="field-row"><Field label="Tên xe" value={vehicleName} onChange={event => setVehicleName(event.target.value)} required /><Field label="Mẫu xe" value={vehicleModel} onChange={event => setVehicleModel(event.target.value)} required /></div>
            <div className="field-row"><label className="field"><span className="field-label">Loại đầu nối</span><select value={vehicleConnector} onChange={event => setVehicleConnector(event.target.value)}>{(connectorTypes.length ? connectorTypes : [station?.connectorType ?? 'STANDARD']).map(type => <option value={type} key={type}>{connectorLabel(type)}</option>)}</select></label><Field label="Dung lượng pin (kWh)" type="number" min="0.1" step="0.1" value={vehicleCapacity} onChange={event => setVehicleCapacity(event.target.value)} required /></div>
            <ActionButton type="button" className="button-secondary" busy={savingVehicle} onClick={() => void saveVehicle()}>Lưu phương tiện</ActionButton>
          </div> : <div className="reserve-vehicle-inline"><span>{selectedVehicle ? `${selectedVehicle.model} · ${formatNumber(selectedVehicle.batteryCapacityKwh)} kWh` : 'Chọn phương tiện đã lưu để xem cổng tương thích.'}</span><button type="button" className="button button-quiet button-small" onClick={() => setAddVehicle(true)}>Thêm xe</button></div>}
          <div className="field-row"><Field label="Ngày đến" type="date" min={campusDateInput()} value={date} onChange={event => { setDate(event.target.value); setSlot(''); setPortId(''); resetReservationKey() }} required /><Field label="Giờ đến" type="time" step="60" min={date === campusDateInput() ? campusTimeInput() : undefined} value={slot} onChange={event => { setSlot(event.target.value); setPortId(''); resetReservationKey() }} hint="Chọn giờ đến chính xác đến phút; thời lượng giữ cổng là 4 giờ." required /></div>
          <section className="reserve-port-section" aria-labelledby="reserve-ports-title"><div className="panel-heading"><div><p className="eyebrow">CỔNG TƯƠNG THÍCH</p><h3 id="reserve-ports-title">Chọn cổng sạc</h3></div>{slot && <span className="data-count">{availablePorts?.length ?? 0} cổng trống</span>}</div>
            {!vehicleId || !startAt ? <p className="reserve-empty">Chọn xe và giờ đến để xem cổng tương thích còn trống.</p> : !startInFuture ? <p className="reserve-empty">Giờ đã chọn không còn ở tương lai. Vui lòng chọn giờ đến muộn hơn hiện tại ít nhất 2 phút.</p> : availabilityLoading ? <Loading label="Đang kiểm tra lịch cổng" /> : availabilityError ? <LoadError onRetry={retryAvailability} /> : !availablePorts?.length ? <p className="reserve-empty">Không có cổng tương thích còn trống vào giờ này. Vui lòng chọn giờ khác.</p> : <div className="reserve-port-grid">{availablePorts.map(port => <label className={`reserve-port-option ${portId === port.portId ? 'reserve-port-selected' : ''}`} key={port.portId}><input type="radio" name="reserve-port" value={port.portId} checked={portId === port.portId} onChange={() => { setPortId(port.portId); resetReservationKey() }} /><span className="reserve-port-icon"><PlugZap size={20} /></span><span className="reserve-port-copy"><strong>{portLabel(port.portId, portNames)} · {connectorLabel(port.connectorType ?? 'STANDARD')}</strong><small>Tối đa {formatNumber(port.maxPowerKw ?? 3)} kW · Tương thích với xe</small></span><span className="reserve-port-ready">Trống</span></label>)}</div>}
          </section>
          {startAt && <div className="reserve-policy-card"><div><span>Thời gian giữ cổng</span><strong>{formatDateTime(startIso)} – {formatDateTime(endIso)}</strong></div><div><span>Hạn bắt đầu sạc</span><strong>15 phút từ giờ hẹn</strong></div><div><span>Phí đặt chỗ</span><strong>Chưa áp dụng</strong></div><small>Quá thời gian chờ, lượt đặt sẽ hết hạn và cổng được giải phóng. Nhắc lịch hiển thị trong ứng dụng khi bạn mở trang lịch đặt chỗ; chưa gửi SMS/email.</small></div>}
          <FormError message={formError} /><div className="form-actions"><ActionButton type="submit" className="button-primary" busy={busy} disabled={!stationId || !vehicleId || !startAt || !startInFuture || !portId || !chosenPort}>Đặt chỗ <ArrowUpRight size={16} /></ActionButton><Link className="button button-quiet" to="/bookings">Quay lại</Link></div>
        </form>}
      </section>}
  </>
}

function campusInstant(value: string) { return `${value.length === 16 ? `${value}:00` : value}+07:00` }
function campusDateInput() {
  const parts = new Intl.DateTimeFormat('en', { timeZone: 'Asia/Ho_Chi_Minh', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const part = (type: string) => parts.find(item => item.type === type)?.value ?? ''
  return `${part('year')}-${part('month')}-${part('day')}`
}
function campusTimeInput() {
  return new Intl.DateTimeFormat('en-GB', { timeZone: 'Asia/Ho_Chi_Minh', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(Date.now() + 120_000))
}

function BookingsPage() {
  const { data, loading, error, retry, setData } = useRead(api.bookings, 'bookings', 60_000)
  const { data: stations } = useRead(api.stations, 'bookings-stations')
  const stationIds = stations?.map(item => item.stationId) ?? []
  const { data: stationPortGroups } = useRead(() => Promise.all(stationIds.map(id => api.ports(id))), `bookings-port-details-${stationIds.join(',')}`)
  const { data: tariffGroups, loading: tariffsLoading, error: tariffsError } = useRead(() => Promise.all(stationIds.map(id => api.currentTariff(id))), `bookings-tariffs-${stationIds.join(',')}`)
  const { data: vehicles, retry: reloadVehicles } = useRead(api.vehicles, 'my-vehicles')
  const { notify } = useApp()
  const requestKeys = useRequestKeys()
  const [selectedBooking, setSelectedBooking] = useState<Booking | null>(null)
  const [battery, setBattery] = useState(''); const [vehicleId, setVehicleId] = useState('')
  const [targetType, setTargetType] = useState<ChargeOptions['targetType']>('FULL'); const [targetPercent, setTargetPercent] = useState('80'); const [targetBudget, setTargetBudget] = useState('200000')
  const [newVehicle, setNewVehicle] = useState(false); const [vehicleName, setVehicleName] = useState('Xe máy của tôi'); const [vehicleModel, setVehicleModel] = useState('Xe máy điện'); const [vehicleCapacity, setVehicleCapacity] = useState('3')
  const [vehicleConnector, setVehicleConnector] = useState('STANDARD')
  const [quoteKey, setQuoteKey] = useState(''); const [busyId, setBusyId] = useState(''); const [actionError, setActionError] = useState('')
  const portNames = useMemo(() => new Map((stationPortGroups ?? []).flatMap(group => group.map(port => [port.portId, uppercasePortName(port.displayName)] as const))), [stationPortGroups])
  const notifiedReminders = useRef(new Set<string>())
  useEffect(() => {
    const now = Date.now()
    const due = (data ?? []).filter(item => item.status === 'CONFIRMED' && new Date(item.startAt).getTime() > now && new Date(item.startAt).getTime() - now <= 15 * 60 * 1000)
      .filter(item => {
        const key = `campus-charge-booking-reminder:${item.bookingId}`
        if (notifiedReminders.current.has(key)) return false
        try { if (localStorage.getItem(key)) return false; localStorage.setItem(key, 'shown') } catch { /* The in-memory set still deduplicates this page visit. */ }
        notifiedReminders.current.add(key)
        return true
      })
    if (!due.length) return
    notify({ kind: 'info', text: `Nhắc lịch: còn ít phút đến giờ đặt ${due.map(item => `cổng ${portLabel(item.portId, portNames)}`).join(', ')}. Bạn có 15 phút từ giờ hẹn để bắt đầu sạc.` })
  }, [data, notify, portNames])
  const navigate = useNavigate()
  const stationNames = new Map(stations?.map(item => [item.stationId, item.name]))
  const stationMap = new Map(stations?.map(item => [item.stationId, item]))
  const allPorts = stationPortGroups?.flat() ?? []
  const portsById = new Map(allPorts.map(port => [port.portId, port]))
  const tariffsByStation = new Map(stationIds.map((stationId, index) => [stationId, tariffGroups?.[index]?.[0]]))
  const selectedTariff = selectedBooking ? tariffsByStation.get(selectedBooking.stationId) : undefined
  const tariffLabel = selectedTariff ? `${formatVnd(selectedTariff.pricePerKwh)} / kWh` : tariffsLoading ? 'Đang tải biểu giá…' : tariffsError ? 'Chưa tải được biểu giá' : 'Chưa có biểu giá'
  const selectedVehicle = vehicles?.find(item => item.vehicleId === vehicleId)
  const assignedConnector = selectedBooking ? portsById.get(selectedBooking.portId)?.connectorType ?? stationMap.get(selectedBooking.stationId)?.connectorType ?? 'STANDARD' : ''
  const connectorCompatible = Boolean(selectedVehicle && selectedVehicle.connectorType === assignedConnector)
  const chargeOptions = useMemo<ChargeOptions | undefined>(() => selectedBooking && vehicleId && connectorCompatible && Number.isInteger(Number(battery)) && Number(battery) >= 0 && Number(battery) <= 99 &&
    (targetType !== 'BATTERY_PERCENT' || Number.isInteger(Number(targetPercent)) && Number(targetPercent) > Number(battery) && Number(targetPercent) <= 100) &&
    (targetType !== 'BUDGET' || Number.isInteger(Number(targetBudget)) && Number(targetBudget) > 0) ? {
    vehicleId, initialBatteryPercent: Number(battery), targetType,
    targetBatteryPercent: targetType === 'BATTERY_PERCENT' ? Number(targetPercent) : null,
    targetBudgetVnd: targetType === 'BUDGET' ? Number(targetBudget) : null,
  } : undefined, [selectedBooking?.bookingId, vehicleId, battery, targetType, targetPercent, targetBudget, connectorCompatible])
  const { data: quote, loading: quoteLoading } = useRead(() => chargeOptions && selectedBooking ? api.quoteCharge(selectedBooking.bookingId, chargeOptions) : Promise.resolve(null), `charge-quote-${selectedBooking?.bookingId}-${quoteKey}`)
  const resetQuote = () => { setQuoteKey(crypto.randomUUID()); if (selectedBooking) requestKeys.reset(`start:${selectedBooking.bookingId}`) }
  const saveVehicle = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); if (!selectedBooking) return
    try {
      const created = await api.createVehicle({ name: vehicleName, model: vehicleModel, connectorType: vehicleConnector, batteryCapacityKwh: Number(vehicleCapacity) })
      reloadVehicles(); setVehicleId(created.vehicleId); setNewVehicle(false); resetQuote(); setActionError('')
    } catch (reason) { setActionError(messageFor(reason)) }
  }
  const begin = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    if (!selectedBooking || !chargeOptions || !quote) return
    const percent = Number(battery)
    if (!Number.isInteger(percent) || percent < 0 || percent > 99) { setActionError('Nhập mức pin hiện tại từ 0 đến 99%.'); return }
    const bookingId = selectedBooking.bookingId
    setBusyId(bookingId); setActionError('')
    try { const result = await api.start(bookingId, chargeOptions, requestKeys.get(`start:${bookingId}`)); requestKeys.reset(`start:${bookingId}`); notify({ kind: 'info', text: 'Đang chờ thiết bị xác nhận bắt đầu sạc.' }); navigate(`/sessions/${result.sessionId}`) }
    catch (reason) { setActionError(messageFor(reason)) } finally { setBusyId('') }
  }
  const cancel = async (bookingId: string) => {
    setBusyId(bookingId); setActionError('')
    try { const updated = await api.cancelBooking(bookingId, requestKeys.get(`cancel:${bookingId}`)); requestKeys.reset(`cancel:${bookingId}`); setData((data ?? []).map(item => item.bookingId === bookingId ? updated : item)); notify({ kind: 'success', text: 'Đã hủy lượt đặt chỗ.' }) }
    catch (reason) { setActionError(messageFor(reason)) } finally { setBusyId('') }
  }
  return <><PageHeader eyebrow="LỊCH SỬ ĐẶT CHỖ" title="Đặt chỗ của bạn" detail="Xem cổng đã phân bổ hoặc bắt đầu phiên khi đến khung giờ." action={<Link to="/bookings/new" className="button button-primary"><CalendarDays size={17} />Đặt chỗ mới</Link>} />
    <FormError message={actionError} />
    <section className="panel panel-spacious">{loading ? <Loading label="Đang tải lịch đặt chỗ" /> : error ? <LoadError onRetry={retry} /> : !data?.length ? <EmptyState title="Bạn chưa có lượt đặt chỗ" detail="Chọn xe, cổng tương thích, ngày và giờ đến để đặt chỗ." action={<Link className="button button-primary" to="/bookings/new">Đặt chỗ sạc</Link>} /> : <div className="booking-list">{data.map(item => <article className="booking-row" key={item.bookingId}>
      <div className="booking-date"><CalendarDays size={17} /><div><strong>{formatDate(item.startAt)}</strong><small>{formatTime(item.startAt)} – {formatTime(item.endAt)}</small>{item.status === 'CONFIRMED' && <small>Chỉ bắt đầu từ {formatTime(item.startAt)} đến {formatTime(new Date(new Date(item.startAt).getTime() + 15 * 60 * 1000).toISOString())}</small>}</div></div>
      <div className="booking-station"><strong>{stationNames.get(item.stationId) ?? 'Trạm sạc'}</strong><small>Cổng {portLabel(item.portId, portNames)}</small></div><StatusBadge value={item.status} />
      {item.status === 'CONFIRMED' && <div className="booking-actions"><ActionButton busy={busyId === item.bookingId} disabled={Boolean(busyId) || !bookingStartReady(item)} onClick={() => { setSelectedBooking(item); setBattery(''); setVehicleId(item.vehicleId ?? vehicles?.[0]?.vehicleId ?? ''); setVehicleConnector(portsById.get(item.portId)?.connectorType ?? stationMap.get(item.stationId)?.connectorType ?? 'STANDARD'); setTargetType('FULL'); setNewVehicle(false); setActionError(''); setQuoteKey(crypto.randomUUID()) }} className="button-primary button-small">Bắt đầu sạc</ActionButton><button className="button button-quiet button-small" disabled={Boolean(busyId)} onClick={() => cancel(item.bookingId)}>Hủy</button></div>}
    </article>)}</div>}</section>
    {selectedBooking && <div className="modal-backdrop" onMouseDown={event => { if (event.target === event.currentTarget && !busyId) { setSelectedBooking(null); setActionError('') } }}>
      <section className="charge-start-dialog charge-confirm-dialog" role="dialog" aria-modal="true" aria-labelledby="charge-start-title">
        <button type="button" className="dialog-close" aria-label="Đóng" disabled={Boolean(busyId)} onClick={() => { setSelectedBooking(null); setActionError('') }}>×</button>
        <header className="confirm-dialog-heading"><span className="dialog-icon"><BatteryCharging size={22} /></span><div><p className="eyebrow">XÁC NHẬN THÔNG TIN</p><h2 id="charge-start-title">Bắt đầu phiên sạc</h2></div></header>
        <div className="confirm-port-card"><span className="confirm-port-icon"><PlugZap size={22} /></span><div><strong>{portLabel(selectedBooking.portId, portNames)}</strong><small>{stationNames.get(selectedBooking.stationId) ?? 'Trạm sạc'} · {connectorLabel(portsById.get(selectedBooking.portId)?.connectorType ?? stationMap.get(selectedBooking.stationId)?.connectorType ?? 'STANDARD')}</small></div><span className="confirm-assigned">Đã giữ chỗ</span></div>
        {!newVehicle ? <div className="confirm-vehicle-card"><span className="confirm-vehicle-icon"><Zap size={22} /></span><div className="confirm-vehicle-select"><strong>Phương tiện</strong><select value={vehicleId} disabled={Boolean(selectedBooking.vehicleId)} onChange={event => { setVehicleId(event.target.value); resetQuote() }} aria-label="Chọn phương tiện"><option value="">Chọn xe đã lưu</option>{vehicles?.map(vehicle => <option key={vehicle.vehicleId} value={vehicle.vehicleId}>{vehicle.name} · {vehicle.model}</option>)}</select>{selectedVehicle && <small>{selectedVehicle.batteryCapacityKwh} kWh · {connectorLabel(selectedVehicle.connectorType)}{selectedBooking.vehicleId ? ' · Xe đã chọn khi đặt cổng' : ''}</small>}</div>{!selectedBooking.vehicleId && <button type="button" className="button button-secondary button-small" onClick={() => setNewVehicle(true)}>Thêm xe</button>}</div> : <form className="vehicle-create-inline" onSubmit={saveVehicle}><strong>Thêm phương tiện</strong><label>Tên xe<input required value={vehicleName} onChange={event => setVehicleName(event.target.value)} /></label><label>Mẫu xe<input required value={vehicleModel} onChange={event => setVehicleModel(event.target.value)} /></label><label>Loại đầu nối<select value={vehicleConnector} onChange={event => setVehicleConnector(event.target.value)}><option value="STANDARD">Phổ thông</option><option value="TYPE_2">Type 2</option><option value="GB_T">GB/T</option><option value="CCS2">CCS2</option><option value="CHADEMO">CHAdeMO</option></select></label><label>Dung lượng pin (kWh)<input required type="number" min="0.1" step="0.1" value={vehicleCapacity} onChange={event => setVehicleCapacity(event.target.value)} /></label><small>Loại đầu nối cần tương thích với cổng đã giữ.</small><div><button type="button" className="button button-secondary button-small" onClick={() => setNewVehicle(false)}>Hủy</button><button type="submit" className="button button-primary button-small">Lưu phương tiện</button></div></form>}
        <div className="confirm-charge-settings"><div><span>Pin hiện tại</span><label className="dialog-battery-input"><input autoFocus required form="charge-start-form" type="number" min="0" max="99" step="1" value={battery} aria-label="Pin hiện tại (%)" onChange={event => { setBattery(event.target.value); resetQuote() }} /><b>%</b></label></div><div className="charge-target-control"><span>Mục tiêu sạc</span><select value={targetType} onChange={event => { setTargetType(event.target.value as ChargeOptions['targetType']); resetQuote() }}><option value="FULL">Sạc đến đầy</option><option value="BATTERY_PERCENT">Đến mức pin</option><option value="BUDGET">Theo ngân sách</option></select>{targetType === 'BATTERY_PERCENT' ? <label><input type="number" min={Math.max(1, Number(battery) + 1)} max="100" value={targetPercent} onChange={event => { setTargetPercent(event.target.value); resetQuote() }} /> % pin</label> : targetType === 'BUDGET' ? <label><input type="number" min="1000" step="1000" value={targetBudget} onChange={event => { setTargetBudget(event.target.value); resetQuote() }} /> đồng</label> : <small>Hệ thống gửi lệnh dừng khi pin đạt 100%.</small>}</div></div>
        <div className="confirm-cost-card"><p><span>Dung lượng pin xe</span><strong>{quote ? `${formatNumber(quote.batteryCapacityKwh)} kWh` : selectedVehicle ? `${formatNumber(selectedVehicle.batteryCapacityKwh)} kWh` : '—'}</strong></p><p><span>Đơn giá hiện hành</span><strong>{quote ? `${formatVnd(quote.pricePerKwh)} / kWh` : tariffLabel}</strong></p><p><span>Điện năng dự kiến</span><strong>{quote ? `${quote.estimatedEnergyKwh.toFixed(2)} kWh` : quoteLoading ? 'Đang tính…' : '—'}</strong></p><p><span>Chi phí ước tính</span><strong>{quote ? formatVnd(quote.estimatedCostVnd) : '—'}</strong></p><small>Điện năng dự kiến dựa trên dung lượng pin xe và mức pin bắt đầu. Chi phí cuối cùng được chốt theo số đo thiết bị; bạn thanh toán qua VNPay.</small></div>
        {actionError && <FormError message={actionError} />}
        {selectedVehicle && !connectorCompatible && <p className="form-hint form-hint-error">Loại đầu nối của xe không tương thích với cổng {portLabel(selectedBooking.portId, portNames)}.</p>}
        {!chargeOptions && battery !== '' && <p className="form-hint">Kiểm tra pin hiện tại, mục tiêu pin phải cao hơn hiện tại hoặc nhập ngân sách hợp lệ.</p>}
        <form id="charge-start-form" className="confirm-dialog-actions" onSubmit={begin}><button type="button" className="button button-secondary" disabled={Boolean(busyId)} onClick={() => { setSelectedBooking(null); setActionError('') }}>Quay lại</button><ActionButton type="submit" busy={busyId === selectedBooking.bookingId} disabled={Boolean(busyId) || !quote || !chargeOptions || !selectedVehicle} className="button-primary"><Zap size={16} />Xác nhận & bắt đầu sạc</ActionButton></form>
      </section>
    </div>}
  </>
}

function SessionsPage() {
  const { data, loading, error, retry, setData } = useRead(api.sessions, 'sessions')
  const portNames = usePortNames('sessions-port-names')
  const [pollError, setPollError] = useState(false)
  useEffect(() => {
    if (!data?.some(item => ['START_REQUESTED', 'CHARGING', 'STOP_REQUESTED'].includes(item.status))) return
    let active = true; let timer = 0
    const poll = async () => {
      try { const current = await api.sessions(); if (active) { setData(current); setPollError(false) } }
      catch { if (active) setPollError(true) }
      finally { if (active) timer = window.setTimeout(poll, 2000) }
    }
    timer = window.setTimeout(poll, 2000)
    return () => { active = false; window.clearTimeout(timer) }
  }, [data])
  return <><PageHeader eyebrow="LƯỢT SẠC CỦA BẠN" title="Phiên sạc" detail="Trạng thái thiết bị được cập nhật trong khi phiên đang hoạt động." />
    {pollError && <NoticeBox notice={{ kind: 'error', text: 'Mất kết nối khi cập nhật trạng thái. Đang giữ số liệu gần nhất và sẽ thử lại.' }} />}
    <section className="panel panel-spacious">{loading ? <Loading label="Đang tải phiên sạc" /> : error ? <LoadError onRetry={retry} /> : !data?.length ? <EmptyState title="Chưa có phiên sạc" detail="Khi bắt đầu sạc, trạng thái phiên sẽ xuất hiện tại đây." action={<Link to="/bookings" className="button button-primary">Xem đặt chỗ</Link>} /> : <div className="session-list">{data.map(item => <article className="session-row" key={item.sessionId}>
      <div className="session-row-main"><div className="session-icon"><BatteryCharging size={19} /></div><div><Link to={`/sessions/${item.sessionId}`} className="session-title">Phiên sạc · {formatDate(item.startedAt)}</Link><p>{portLabel(item.portId, portNames)} · {formatNumber(item.energyKwh)} kWh</p></div></div><div className="session-status"><StatusBadge value={item.status} /></div>
      <div className="session-row-end"><strong>{item.totalFee === null ? 'Đang tính phí' : formatVnd(item.totalFee)}</strong><small>{item.status === 'COMPLETED' ? 'Tổng phí' : `${item.batteryPercent}% pin`}</small></div>
      <Link to={`/sessions/${item.sessionId}`} className="button button-secondary button-small session-details-link">Xem chi tiết <ArrowUpRight size={14} /></Link>
      {item.completionReason === 'DEVICE_DISCONNECTED' && <div className="session-safety-alert" role="status">Phiên đã kết thúc do mất kết nối, chốt theo số liệu cuối cùng nhận được. Cổng vẫn bị khóa để admin xử lý sự cố.</div>}
      {item.deviceStatus === 'FAULTED' && item.commandReason?.includes('OVERHEAT') && <div className="session-safety-alert" role="alert">CẢNH BÁO QUÁ NHIỆT: cổng đã bị khóa do thiết bị báo nhiệt độ cao. Admin đã được thông báo. {item.status === 'COMPLETED' ? 'Phiên sạc đã dừng.' : 'Đang chờ thiết bị xác nhận dừng phiên.'}</div>}
      {item.status === 'COMPLETED' && <div className="session-payment"><StatusBadge value={item.paymentStatus === 'SUCCEEDED' ? 'SUCCEEDED' : item.paymentStatus === 'PENDING' ? 'PENDING' : 'UNPAID'} /></div>}
    </article>)}</div>}</section>
  </>
}

function SessionPage() {
  const { id = '' } = useParams()
  const { data, loading, error, retry, setData } = useRead(() => api.session(id), `session-${id}`)
  const portNames = usePortNames('session-detail-port-names')
  const { notify } = useApp()
  const requestKeys = useRequestKeys()
  const [busy, setBusy] = useState(false); const [actionError, setActionError] = useState('')
  const [pollError, setPollError] = useState(false)
  const targetNoticeShown = useRef('')
  useEffect(() => {
    if (!data || !['START_REQUESTED', 'CHARGING', 'STOP_REQUESTED'].includes(data.status)) return
    let active = true; let timer = 0
    const poll = async () => {
      try { const current = await api.session(id); if (active) { setData(current); setPollError(false) } }
      catch { if (active) setPollError(true) }
      finally { if (active) timer = window.setTimeout(poll, 2000) }
    }
    timer = window.setTimeout(poll, 2000)
    return () => { active = false; window.clearTimeout(timer) }
  }, [data?.status, id])
  useEffect(() => {
    if (!data || !['STOP_REQUESTED', 'COMPLETED'].includes(data.status) || data.commandReason !== 'CHARGE_TARGET_REACHED' || targetNoticeShown.current === id) return
    const storageKey = `campus-charge-target-notified:${id}`
    try { if (localStorage.getItem(storageKey) === '1') { targetNoticeShown.current = id; return } } catch { /* In-app notice still works when browser storage is unavailable. */ }
    targetNoticeShown.current = id
    try { localStorage.setItem(storageKey, '1') } catch { /* The ref prevents duplicate notices during this visit. */ }
    const target = data.targetType === 'BATTERY_PERCENT' ? `${data.targetBatteryPercent}% pin`
      : data.targetType === 'BUDGET' ? `ngân sách ${formatVnd(data.targetBudgetVnd ?? 0)}` : '100% pin'
    notify({ kind: 'info', text: `Đã đạt ${target}. Đã gửi lệnh dừng; phiên chỉ kết thúc khi thiết bị xác nhận.` })
  }, [data?.status, data?.commandReason, id, notify])
  const stop = async () => { setBusy(true); setActionError(''); try { const value = await api.stop(id, requestKeys.get(`stop:${id}`)); requestKeys.reset(`stop:${id}`); setData(value); notify({ kind: 'info', text: 'Đã gửi yêu cầu dừng. Phiên chỉ kết thúc sau khi thiết bị xác nhận.' }) } catch (reason) { setActionError(messageFor(reason)) } finally { setBusy(false) } }
  const payRemainder = async () => {
    if (data?.paymentStatus === 'PENDING' && data.paymentRedirectUrl) { window.location.assign(data.paymentRedirectUrl); return }
    setBusy(true); setActionError('')
    try {
      const payment = await api.startPayment(id, requestKeys.get(`payment:${id}`))
      if (!payment.redirectUrl) throw new Error('Không nhận được đường dẫn thanh toán từ VNPay.')
      window.location.assign(payment.redirectUrl)
    } catch (reason) { setActionError(messageFor(reason)); api.session(id).then(setData).catch(() => {}); setBusy(false) }
  }
  if (loading) return <Loading label="Đang tải phiên sạc" />
  if (error || !data) return <LoadError onRetry={retry} />
  const awaitingStart = data.status === 'START_REQUESTED'
  const awaitingStop = data.status === 'STOP_REQUESTED'
  const overheatFault = data.deviceStatus === 'FAULTED' && data.commandReason?.includes('OVERHEAT')
  const chargingStartedAt = data.chargingStartedAt ? new Date(data.chargingStartedAt) : null
  const durationSeconds = chargingStartedAt ? Math.max(0, Math.floor(((data.endedAt ? new Date(data.endedAt) : new Date()).getTime() - chargingStartedAt.getTime()) / 1000)) : 0
  const duration = chargingStartedAt ? `${String(Math.floor(durationSeconds / 3600)).padStart(2, '0')}:${String(Math.floor(durationSeconds % 3600 / 60)).padStart(2, '0')}:${String(durationSeconds % 60).padStart(2, '0')}` : '—'
  const recentPowerKw = data.powerKw != null && data.powerKw > 0 ? data.powerKw : null
  const targetPercent = data.targetType === 'BATTERY_PERCENT' ? data.targetBatteryPercent ?? 100 : 100
  const targetLabel = data.targetType === 'BATTERY_PERCENT' ? `${targetPercent}%` : data.targetType === 'BUDGET' ? `Ngân sách ${formatVnd(data.targetBudgetVnd ?? 0)}` : 'Đầy pin · 100%'
  const targetReached = data.commandReason === 'CHARGE_TARGET_REACHED'
  const targetEnergyKwh = data.targetType === 'BUDGET' && data.pricePerKwh > 0
    ? (data.targetBudgetVnd ?? 0) / data.pricePerKwh
    : data.batteryCapacityKwh * Math.max(0, targetPercent - data.initialBatteryPercent) / 100
  const remainingSeconds = data.status === 'CHARGING' && recentPowerKw && targetEnergyKwh !== null
    ? Math.max(0, Math.ceil(Math.max(0, targetEnergyKwh - data.energyKwh) / recentPowerKw * 3600)) : null
  const estimatedFinish = remainingSeconds !== null && !targetReached ? new Date(Date.now() + remainingSeconds * 1000) : null
  const etaLabel = targetReached ? 'Đã đạt mục tiêu' : estimatedFinish ? estimatedFinish.toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }) : 'Đang tính'
  const targetNotice = targetReached
    ? `Đã đạt mục tiêu ${targetLabel}. Hệ thống đã yêu cầu thiết bị dừng; phiên chỉ kết thúc sau khi thiết bị xác nhận.`
    : data.status === 'COMPLETED' ? `Phiên sạc đã kết thúc trước khi đạt mục tiêu ${targetLabel}.`
      : awaitingStop ? 'Đang chờ thiết bị xác nhận lệnh dừng sạc.'
        : `Sẽ thông báo khi đạt ${targetLabel} và gửi yêu cầu dừng sạc.`
  if (data.status === 'COMPLETED') {
    const startedAt = data.chargingStartedAt ?? data.startedAt
    const energyFee = data.energyFeeVnd ?? Math.round(data.totalFee ?? data.energyKwh * data.pricePerKwh)
    const occupancyFee = data.occupancyFeeVnd ?? 0
    const totalFee = data.totalFee ?? energyFee + occupancyFee
    const externalPaid = data.externalPaidVnd ?? 0
    const remainingDue = data.remainingDueVnd ?? Math.max(totalFee - externalPaid, 0)
    return <><PageHeader eyebrow="KẾT QUẢ PHIÊN SẠC" title="Sạc hoàn tất" detail={`${portLabel(data.portId, portNames)} · ${formatDateTime(startedAt)} – ${formatDateTime(data.endedAt ?? startedAt)}`} />
      {actionError && <FormError message={actionError} />}{pollError && <NoticeBox notice={{ kind: 'error', text: 'Không thể làm mới thông tin quyết toán. Hãy tải lại để xem trạng thái mới nhất.' }} />}
      {overheatFault && <NoticeBox notice={{ kind: 'error', text: `Phiên đã dừng do quá nhiệt tại ${portLabel(data.portId, portNames)}. Admin đã được thông báo.` }} />}
      <section className="receipt-layout" aria-labelledby="receipt-title">
        <article className="receipt-card">
          <div className="receipt-success-mark" aria-hidden="true">✓</div>
          <p className="eyebrow">{data.completionReason === 'DEVICE_DISCONNECTED' ? 'ĐÃ CHỐT THEO SỐ LIỆU CUỐI CÙNG' : 'ĐÃ CHỐT THEO XÁC NHẬN THIẾT BỊ'}</p><h2 id="receipt-title">Phiên sạc đã hoàn tất</h2>
          {data.completionReason === 'DEVICE_DISCONNECTED' && <NoticeBox notice={{ kind: 'error', text: `Phiên đã tự kết thúc do mất kết nối tại ${portLabel(data.portId, portNames)}. Phí được chốt theo telemetry cuối cùng đã nhận (${formatNumber(data.energyKwh)} kWh); thiết bị chưa xác nhận lệnh dừng. Cổng vẫn bị khóa và admin đã được báo để xử lý.` }} />}
          <p className="receipt-meta">{formatDateTime(startedAt)} – {formatDateTime(data.endedAt ?? startedAt)} · {portLabel(data.portId, portNames)} · Pin xe {formatNumber(data.batteryCapacityKwh)} kWh</p>
          <div className="receipt-energy"><div><strong>{data.initialBatteryPercent}% → {data.batteryPercent}%</strong><small>Mức pin</small></div><div><strong>{formatNumber(data.energyKwh)} kWh</strong><small>Điện năng đã dùng</small></div></div>
          <div className="receipt-lines">
            <div><span>Tiền điện · {formatNumber(data.energyKwh)} kWh × {formatVnd(data.pricePerKwh)}/kWh</span><strong>{formatVnd(energyFee)}</strong></div>
            <div><span>Phí chiếm chỗ</span><strong>{occupancyFee > 0 ? formatVnd(occupancyFee) : 'Chưa áp dụng'}</strong></div>
            <div className="receipt-total"><span>Tổng chi phí phiên sạc</span><strong>{formatVnd(totalFee)}</strong></div>
          </div>
          {externalPaid > 0 && <div className="receipt-lines"><div><span>Đã thanh toán qua VNPay</span><strong>{formatVnd(externalPaid)}</strong></div></div>}
          <div className={`receipt-balance ${remainingDue > 0 ? 'receipt-balance-due' : 'receipt-balance-paid'}`}>
            <span>{remainingDue > 0 ? 'Còn cần thanh toán' : 'Còn phải trả'}</span><strong>{formatVnd(remainingDue)}</strong>
          </div>
          {remainingDue > 0 ? <div className="receipt-payment-action"><p>{data.paymentStatus === 'PENDING' ? 'Giao dịch đang chờ xử lý. Bạn có thể tiếp tục tại VNPay.' : 'Thanh toán toàn bộ chi phí phiên sạc qua VNPay.'}</p><ActionButton busy={busy} onClick={payRemainder} className="button-primary"><CreditCard size={17} />{data.paymentStatus === 'PENDING' ? 'Tiếp tục thanh toán' : 'Thanh toán qua VNPay'}</ActionButton></div>
            : <div className="notice notice-success receipt-paid-notice">{externalPaid > 0 ? 'Đã thanh toán đầy đủ qua VNPay.' : totalFee === 0 ? 'Phiên sạc không phát sinh phí.' : 'Đang cập nhật trạng thái thanh toán từ VNPay.'}</div>}
          <div className="receipt-actions"><Link to={`/support/incidents?portId=${encodeURIComponent(data.portId)}&sessionId=${encodeURIComponent(data.sessionId)}`} className="button button-secondary"><AlertTriangle size={16} />Báo lỗi phiên này</Link><Link to="/sessions" className="button button-secondary">Danh sách phiên sạc</Link><Link to="/" className="button button-quiet">Về tổng quan</Link></div>
        </article>
      </section>
    </>
  }
  return <><PageHeader eyebrow={`PHIÊN · ${data.sessionId.slice(0, 8).toUpperCase()}`} title="Theo dõi phiên sạc" action={<Link to="/sessions" className="button button-secondary">Tất cả phiên</Link>} />
    {actionError && <FormError message={actionError} />}{pollError && <NoticeBox notice={{ kind: 'error', text: 'Không thể cập nhật trạng thái thiết bị. Đang giữ dữ liệu gần nhất và sẽ thử lại.' }} />}
    {data.deviceStatus === 'OFFLINE' && <NoticeBox notice={{ kind: 'error', text: data.status === 'COMPLETED' && data.completionReason === 'DEVICE_DISCONNECTED'
      ? `Phiên đã kết thúc theo số liệu cuối cùng nhận được (${formatNumber(data.energyKwh)} kWh). Thiết bị chưa xác nhận dừng; cổng vẫn bị khóa và admin đã được thông báo.`
      : 'MẤT KẾT NỐI THIẾT BỊ: cổng không phản hồi. Admin đã được thông báo.' }} />}
    {overheatFault && <NoticeBox notice={{ kind: 'error', text: `CẢNH BÁO QUÁ NHIỆT: ${portLabel(data.portId, portNames)} đã bị khóa và admin đã được thông báo. ${data.status === 'COMPLETED' ? 'Phiên sạc đã dừng.' : 'Đang chờ thiết bị xác nhận dừng phiên.'}` }} />}
    <section className="charge-dashboard">
      <header className="charge-dashboard-head"><div><p className="eyebrow">TRẠM SẠC · {portLabel(data.portId, portNames)}</p><h2>{awaitingStart ? 'Đang kết nối thiết bị' : awaitingStop ? 'Đang dừng sạc' : data.status === 'COMPLETED' ? 'Đã hoàn tất sạc' : 'Đang sạc'}</h2><span className="charge-session-time">{chargingStartedAt ? `Bắt đầu sạc lúc ${formatDateTime(data.chargingStartedAt!)}` : `Đã gửi yêu cầu lúc ${formatDateTime(data.startedAt)}`}</span></div><StatusBadge value={data.status} /></header>
      <div className="charge-dashboard-gauge"><ChargeRail value={data.batteryPercent} detail={<><span>Mục tiêu {targetLabel}</span><small>{estimatedFinish ? `Còn khoảng ${Math.ceil(remainingSeconds! / 60)} phút` : targetReached ? 'Đã đạt mức pin mục tiêu' : data.status === 'COMPLETED' ? 'Phiên đã dừng' : 'Đang tính thời gian hoàn tất'}</small></>} /></div>
      <div className="charge-stat-grid"><div className="charge-stat"><span><Zap size={16} />Điện năng đã dùng</span><strong>{formatNumber(data.energyKwh)} <small>kWh</small></strong></div><div className="charge-stat"><span><Gauge size={16} />Công suất gần nhất</span><strong>{recentPowerKw === null ? 'Đang tính' : <>{formatNumber(recentPowerKw)} <small>kW</small></>}</strong></div><div className="charge-stat"><span><Clock3 size={16} />Thời gian đã sạc</span><strong>{duration}</strong></div><div className="charge-stat charge-stat-fee"><span><CreditCard size={16} />{data.totalFee === null ? 'Chi phí tạm tính' : 'Tổng chi phí'}</span><strong>{data.totalFee === null ? formatVnd(data.energyKwh * data.pricePerKwh) : formatVnd(data.totalFee)}</strong></div></div>
      <div className={`charge-target-notice ${targetReached ? 'charge-target-reached' : ''}`} role="status"><Bell size={17} aria-hidden="true" /><span>{targetNotice}</span><strong>{estimatedFinish ? `Dự kiến xong lúc ${etaLabel}` : targetReached ? 'Đã đạt' : etaLabel}</strong></div>
      <div className="session-target-summary"><span>Mục tiêu: {targetLabel}</span><span>Dung lượng pin xe: {formatNumber(data.batteryCapacityKwh)} kWh</span><span>Thanh toán sau khi phiên kết thúc qua VNPay</span></div>
      {awaitingStart && <div className="notice notice-info" role="status"><span className="spinner spinner-small" aria-hidden="true" />Đang chờ xác nhận bắt đầu từ thiết bị</div>}
      {awaitingStop && <div className="notice notice-info" role="status"><span className="spinner spinner-small" aria-hidden="true" />Đang chờ xác nhận dừng từ thiết bị</div>}
      {data.deliveryPending && <p className="device-pending"><span className="offline-dot" />Thiết bị chưa xác nhận lệnh gần nhất. Trạng thái có thể chưa cập nhật.</p>}
      <Link className="button button-secondary session-report-link" to={`/support/incidents?portId=${encodeURIComponent(data.portId)}&sessionId=${encodeURIComponent(data.sessionId)}`}><AlertTriangle size={16} />Báo lỗi về phiên này</Link>
      {data.status === 'CHARGING' && <ActionButton busy={busy} onClick={stop} className="button-danger"><Zap size={16} />Dừng sạc</ActionButton>}
    </section>
  </>
}

function PaymentReturnPage() {
  const navigate = useNavigate()
  const query = new URLSearchParams(useLocation().search)
  const reference = query.get('vnp_TxnRef') ?? ''
  const signedFields = new URLSearchParams()
  query.forEach((value, key) => { if (key.startsWith('vnp_')) signedFields.append(key, value) })
  const returnQuery = signedFields.toString()
  const { data, loading, error, retry } = useRead(() => api.paymentReturn(returnQuery), `payment-return-${returnQuery}`)
  useEffect(() => { if (data?.status === 'SUCCEEDED' && data.sessionId) navigate(`/sessions/${data.sessionId}`, { replace: true }) }, [data?.status, data?.sessionId, navigate])
  if (!reference) return <><PageHeader title="Không có mã giao dịch" detail="Không tìm thấy thông tin lượt thanh toán trong đường dẫn." /><Link to="/sessions" className="button button-primary">Quay lại phiên sạc</Link></>
  return <><PageHeader eyebrow="KẾT QUẢ THANH TOÁN" title="Kết quả thanh toán" detail="Đang xác minh phản hồi VNPay và cập nhật trạng thái giao dịch." />
    {loading ? <Loading label="Đang kiểm tra trạng thái giao dịch" /> : error ? <LoadError onRetry={retry} /> : <section className="confirmation-card"><div className={`confirmation-icon ${data?.status === 'SUCCEEDED' ? 'confirmation-good' : ''}`}><CreditCard size={22} /></div><p className="eyebrow">THÔNG TIN GIAO DỊCH</p><h2>{data?.status === 'SUCCEEDED' ? 'Thanh toán đã được xác nhận' : 'Trạng thái giao dịch'}</h2><StatusBadge value={data?.status ?? 'PENDING'} /><p>Mã tham chiếu: <code>{reference}</code></p>{data?.sessionId && <Link className="button button-primary" to={`/sessions/${data.sessionId}`}>Xem tổng kết phiên sạc</Link>}</section>}
  </>
}

function IncidentsPage() {
  const { data, loading, error, retry, setData } = useRead(api.incidents, 'incidents', 2000)
  const { data: stations } = useRead(api.stations, 'incident-stations')
  const { data: allPorts, setData: setAllPorts } = useRead(async () => Promise.all((stations ?? []).map(item => api.ports(item.stationId))), `incident-ports-${stations?.length ?? 0}`, 2000)
  const { notify } = useApp(); const [busyId, setBusyId] = useState(''); const [actionError, setActionError] = useState('')
  const seenIncidents = useRef<Set<string> | null>(null)
  const [assignments, setAssignments] = useState<Record<string, { staffName: string; note: string }>>({})
  const [repairReports, setRepairReports] = useState<Record<string, string>>({})
  const ports = allPorts?.flat() ?? []; const portMap = new Map(ports.map(item => [item.portId, item]))
  const portNames = useMemo(() => new Map((allPorts ?? []).flatMap(group=>group.map(port=>[port.portId,uppercasePortName(port.displayName)] as const))), [allPorts])
  useEffect(() => {
    if (!data) return
    const previous = seenIncidents.current
    const fresh = previous ? data.find(item => item.status !== 'RESOLVED' && !previous.has(item.incidentId)) : null
    seenIncidents.current = new Set(data.map(item => item.incidentId))
    if (fresh) notify({ kind: 'error', text: `Có sự cố mới tại ${portNames.get(fresh.portId) ?? uppercasePortName()}: ${fresh.reportType === 'CUSTOMER' ? incidentCategoryLabel(fresh.category) : incidentLabel(fresh.description)}` })
  }, [data, portNames, notify])
  const updateIncident = (updated: Incident) => setData(current => (current ?? []).map(item => item.incidentId === updated.incidentId ? updated : item))
  const act = async (incident: Incident, action: 'assign' | 'repair-reported' | 'resolve') => {
    setBusyId(incident.incidentId); setActionError('')
    try {
      if (action === 'assign') {
        const draft = assignments[incident.incidentId] ?? { staffName: incident.assignedStaffName ?? '', note: incident.assignmentNote ?? '' }
        const value = await api.assignIncident(incident.incidentId, draft)
        updateIncident(value)
        notify({ kind: 'success', text: `Đã tiếp nhận và phân công ${draft.staffName.trim()}.` })
      }
      if (action === 'repair-reported') {
        const value = await api.reportIncidentRepair(incident.incidentId, { report: repairReports[incident.incidentId] ?? '' })
        updateIncident(value)
        notify({ kind: 'success', text: 'Đã ghi nhận nhân viên báo hoàn tất sửa chữa. Hệ thống đang tự kiểm tra an toàn thiết bị.' })
      }
      if (action === 'resolve') {
        const value = await api.resolveIncident(incident.incidentId)
        updateIncident(value)
        setAllPorts(current => current?.map(group => group.map(port => port.portId === incident.portId
          ? { ...port, status: 'AVAILABLE', deviceStatus: 'AVAILABLE', incidentDescription: null, incidentStatus: 'RESOLVED' }
          : port)) ?? [])
        notify({ kind: 'success', text: `${portNames.get(incident.portId) ?? uppercasePortName()} đã được admin xác nhận đưa vào hoạt động.` })
      }
    } catch (reason) { setActionError(messageFor(reason)) } finally { setBusyId('') }
  }
  return <><PageHeader eyebrow="QUẢN TRỊ BỆNH VIỆN" title="Quản lý sự cố" detail="Admin tiếp nhận và phân công nhân viên, ghi nhận báo sửa, sau đó xác nhận đưa cổng vào hoạt động." />
    {actionError && <FormError message={actionError} />}
    <section className="panel panel-spacious">{loading ? <Loading label="Đang tải sự cố" /> : error ? <LoadError onRetry={retry} /> : !data?.length ? <EmptyState title="Không có sự cố mới" detail="Sự cố do người dùng hoặc thiết bị báo sẽ xuất hiện tại đây." /> : <div className="incident-list">{data.map(item => {
      const port = portMap.get(item.portId); const recoveryStatus = item.recoveryStatus;
      const customerReport=item.reportType==='CUSTOMER'; const title=customerReport?incidentCategoryLabel(item.category):incidentLabel(item.description)
      const assignment = assignments[item.incidentId] ?? { staffName: item.assignedStaffName ?? '', note: item.assignmentNote ?? '' }
      const needsDeviceRecovery = item.reportType === 'DEVICE' || ['FAULTED','OFFLINE'].includes(port?.status ?? '')
      return <article className={`incident-row${customerReport?' incident-customer-row':''}`} key={item.incidentId}><div className="incident-severity"><AlertTriangle size={18} /></div><div className="incident-copy"><div className="incident-title-row"><strong>{title}</strong><StatusBadge value={item.status} /></div><p>{item.stationName} · {portNames.get(item.portId) ?? uppercasePortName()} · {formatDateTime(item.reportedAt)}</p>{customerReport&&<><small>Người báo: {item.reporterEmail ?? 'Người dùng'}</small>{item.sessionId&&<small>Phiên: {item.sessionId.slice(0,8).toUpperCase()}</small>}{item.description!==title&&<p className="incident-customer-description">{item.description}</p>}{item.attachmentIds.length>0&&<div className="report-saved-images incident-attachment-images">{item.attachmentIds.map(fileId=><a key={fileId} href={api.incidentAttachmentUrl(item.incidentId,fileId)} target="_blank" rel="noreferrer"><img src={api.incidentAttachmentUrl(item.incidentId,fileId)} alt="Ảnh do khách hàng đính kèm" /></a>)}</div>}</>}{port&&<small>Trạng thái trụ: {statusLabel(port.status)}</small>}
          {item.assignedStaffName && <div className="incident-work-summary"><strong>Nhân viên được phân công: {item.assignedStaffName}</strong>{item.assignmentNote && <p>Ghi chú phân công: {item.assignmentNote}</p>}</div>}
          {item.repairReport && <div className="incident-work-summary"><strong>Nhân viên báo đã xử lý</strong><p>{item.repairReport}</p>{item.repairReportedAt && <small>Ghi nhận {formatDateTime(item.repairReportedAt)}</small>}</div>}
        </div>
        <div className="incident-actions">
          {['OPEN','ASSIGNED'].includes(item.status) && <div className="incident-workflow-form"><strong>{item.status === 'OPEN' ? 'Tiếp nhận và phân công' : 'Cập nhật phân công'}</strong><label className="field"><span className="field-label">Tên nhân viên</span><input value={assignment.staffName} maxLength={120} onChange={event=>setAssignments(current=>({...current,[item.incidentId]:{...assignment,staffName:event.target.value}}))} placeholder="Nhập tên nhân viên được điều đi" /></label><label className="field"><span className="field-label">Ghi chú phân công</span><textarea value={assignment.note} maxLength={1000} onChange={event=>setAssignments(current=>({...current,[item.incidentId]:{...assignment,note:event.target.value}}))} placeholder="Vị trí, nội dung cần kiểm tra…" /></label><ActionButton busy={busyId === item.incidentId} disabled={Boolean(busyId)||!assignment.staffName.trim()} onClick={() => act(item, 'assign')} className="button-secondary button-small">{item.status === 'OPEN' ? 'Tiếp nhận và phân công' : 'Lưu phân công'}</ActionButton></div>}
          {item.status === 'ASSIGNED' && <div className="incident-workflow-form"><strong>Ghi nhận nhân viên báo đã sửa</strong><p>Nhân viên báo kết quả trực tiếp cho admin; nhập nội dung đã xác nhận.</p><label className="field"><span className="field-label">Kết quả sửa chữa</span><textarea required maxLength={2000} value={repairReports[item.incidentId] ?? ''} onChange={event=>setRepairReports(current=>({...current,[item.incidentId]:event.target.value}))} placeholder="Nhân viên đã kiểm tra hoặc sửa những gì?" /></label><ActionButton busy={busyId === item.incidentId} disabled={Boolean(busyId)||!(repairReports[item.incidentId] ?? '').trim()} onClick={() => act(item, 'repair-reported')} className="button-secondary button-small">Ghi nhận đã sửa</ActionButton></div>}
          {item.status === 'REPAIR_REPORTED' && <div className="incident-workflow-form"><strong>Xác nhận</strong>{needsDeviceRecovery && <p>{recoveryStatus === 'ACKNOWLEDGED' ? 'Thiết bị đã xác nhận an toàn sau sửa chữa.' : 'Hệ thống đang yêu cầu thiết bị kết nối lại. Nếu phiên còn chờ dừng, thiết bị phải xác nhận dừng trước khi cổng được mở.'}</p>}<ActionButton busy={busyId === item.incidentId} disabled={Boolean(busyId)||(needsDeviceRecovery&&recoveryStatus!=='ACKNOWLEDGED')} onClick={() => act(item, 'resolve')} className="button-primary button-small">Xác nhận</ActionButton></div>}
        </div></article>
    })}</div>}</section>
  </>
}

function AdminRoutes() {
  return <Routes><Route path="users" element={<UsersAdmin />} /><Route path="stations" element={<ChargersAdmin />} /><Route path="tariffs" element={<TariffsAdmin />} /><Route path="reports" element={<ReportsAdmin />} /><Route path="*" element={<Navigate to="/admin/reports" replace />} /></Routes>
}

function UsersAdmin() {
  const { data, loading, error, retry, setData } = useRead(api.users, 'admin-users')
  const { notify } = useApp(); const [busyId, setBusyId] = useState(''); const [formError, setFormError] = useState('')
  const save = async (user: User, role: Role, enabled: boolean) => {
    setBusyId(user.userId); setFormError('')
    try { const value = await api.updateUser(user.userId, { role, enabled }); setData((data ?? []).map(item => item.userId === value.userId ? value : item)); notify({ kind: 'success', text: 'Đã lưu quyền tài khoản.' }) }
    catch (reason) { setFormError(messageFor(reason)) } finally { setBusyId('') }
  }
  return <><PageHeader eyebrow="QUẢN TRỊ HỆ THỐNG" title="Tài khoản" detail="Phân vai trò và bật hoặc khóa quyền đăng nhập." />{formError && <FormError message={formError} />}
    <section className="panel panel-spacious"><div className="panel-heading"><div><p className="eyebrow">NGƯỜI DÙNG</p><h2>Quyền truy cập</h2></div><span className="data-count">{data?.length ?? 0} tài khoản</span></div>
      {loading ? <Loading label="Đang tải tài khoản" /> : error ? <LoadError onRetry={retry} /> : !data?.length ? <EmptyState title="Chưa có tài khoản" detail="Tài khoản sẽ xuất hiện sau khi người dùng đăng ký." /> : <DataTable label="Danh sách tài khoản"><thead><tr><th>Thư điện tử</th><th>Vai trò</th><th>Trạng thái</th><th>Thao tác</th></tr></thead><tbody>{data.map(user => <tr key={user.userId}><td><strong>{user.email}</strong></td><td><select aria-label={`Vai trò của ${user.email}`} value={user.role} onChange={event => save(user, event.target.value as Role, user.enabled)} disabled={Boolean(busyId)}><option value="USER">Người dùng</option><option value="ADMIN">Quản trị viên</option></select></td><td><StatusBadge value={user.enabled ? 'AVAILABLE' : 'DISABLED'} /></td><td><button className="button button-secondary button-small" disabled={Boolean(busyId)} onClick={() => save(user, user.role, !user.enabled)}>{busyId === user.userId ? 'Đang lưu…' : user.enabled ? 'Khóa tài khoản' : 'Mở tài khoản'}</button></td></tr>)}</tbody></DataTable>}
    </section></>
}

function ChargersAdmin() {
  const { data, loading, error, retry, setData } = useRead(api.chargers, 'admin-chargers', 5000)
  const { notify } = useApp()
  const [busyId, setBusyId] = useState(''); const [actionError, setActionError] = useState('')
  const [mode, setMode] = useState<'AC' | 'DC'>('AC'); const [connector, setConnector] = useState('TYPE_2'); const [power, setPower] = useState('10')
  const [powerDrafts, setPowerDrafts] = useState<Record<string,string>>({})
  const create = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const maxPowerKw = Number(power); const min = mode === 'AC' ? 10 : 30; const max = mode === 'AC' ? 22 : 150
    if (!Number.isFinite(maxPowerKw) || maxPowerKw < min || maxPowerKw > max) { setActionError(`Công suất trụ ${mode} phải từ ${min} đến ${max} kW.`); return }
    setBusyId('create'); setActionError('')
    try {
      const charger = await api.createCharger({ chargingMode: mode, connectorType: connector, maxPowerKw })
      setData([...(data ?? []), charger]); setPower(mode === 'AC' ? '3' : '20')
      notify({ kind: 'success', text: `Đã tạo trụ ${uppercasePortName(charger.displayName)}. Trụ sẽ sẵn sàng sau khi thiết bị kết nối.` })
    } catch (reason) { setActionError(messageFor(reason)) } finally { setBusyId('') }
  }
  const toggle = async (port: Port) => {
    setBusyId(port.portId); setActionError('')
    try {
      const updated = await api.updatePort(port.portId, port.status === 'DISABLED')
      setData((data ?? []).map(item => item.portId === updated.portId ? { ...item, status: updated.status, deviceStatus: updated.status } : item))
    } catch (reason) { setActionError(messageFor(reason)) } finally { setBusyId('') }
  }
  const savePower = async (port: Port) => {
    const mode = port.chargingMode ?? 'AC'; const min = mode === 'AC' ? 10 : 30; const max = mode === 'AC' ? 22 : 150
    const value=Number(powerDrafts[port.portId] ?? port.maxPowerKw ?? min)
    if(!Number.isFinite(value) || value<min || value>max) { setActionError(`Công suất trụ ${mode} phải từ ${min} đến ${max} kW.`); return }
    setBusyId(port.portId); setActionError('')
    try {
      const updated=await api.updatePortPower(port.portId,value)
      setData((data ?? []).map(item=>item.portId===port.portId?{...item,maxPowerKw:updated.maxPowerKw}:item))
      setPowerDrafts(current=>({...current,[port.portId]:String(updated.maxPowerKw)}))
      notify({kind:'success',text:`Đã cập nhật công suất tối đa trụ ${uppercasePortName(port.displayName)}.`})
    } catch(reason) { setActionError(messageFor(reason)) } finally { setBusyId('') }
  }
  return <><PageHeader eyebrow="QUẢN TRỊ BỆNH VIỆN" title="Trụ sạc" detail="Thêm và theo dõi các trụ AC/DC trong khuôn viên bệnh viện." />
    <section className="panel"><div className="panel-heading"><div><p className="eyebrow">THÊM THIẾT BỊ</p><h2>Cấu hình trụ sạc</h2></div></div>
      <form noValidate className="inline-form" onSubmit={create}>
        <label className="field"><span className="field-label">Loại trụ</span><select value={mode} onChange={event => { const next = event.target.value as 'AC' | 'DC'; setMode(next); setConnector(next === 'AC' ? 'TYPE_2' : 'CCS2'); setPower(next === 'AC' ? '10' : '30') }}><option value="AC">AC</option><option value="DC">DC</option></select></label>
        <label className="field"><span className="field-label">Chuẩn cổng</span><select value={connector} onChange={event => setConnector(event.target.value)}><option value="STANDARD">Phổ thông</option><option value="TYPE_2">Type 2</option><option value="GB_T">GB/T</option><option value="CCS2">CCS2</option><option value="CHADEMO">CHAdeMO</option></select></label>
        <Field label="Công suất tối đa (kW)" type="number" min={mode === 'AC' ? '10' : '30'} max={mode === 'AC' ? '22' : '150'} step="0.1" value={power} onChange={event => setPower(event.target.value)} />
        <ActionButton busy={busyId === 'create'} type="submit" className="button-primary"><Plus size={16} />Thêm trụ</ActionButton>
      </form>
    </section>
    {actionError && <FormError message={actionError} />}
    <section className="panel panel-spacious"><div className="panel-heading"><div><p className="eyebrow">THIẾT BỊ BỆNH VIỆN</p><h2>Danh sách trụ sạc</h2></div><span className="data-count">{data?.length ?? 0} trụ</span></div>
      {loading ? <Loading label="Đang tải danh sách trụ" /> : error ? <LoadError onRetry={retry} /> : !data?.length ? <EmptyState title="Chưa có trụ sạc" detail="Thêm trụ AC hoặc DC để bắt đầu cấu hình thiết bị." /> : <div className="station-port-sublist">{data.map(port => <div className="admin-port-row" key={port.portId}>
    <div className="admin-port-identity"><strong>{uppercasePortName(port.displayName)}</strong><small>{port.chargingMode ?? 'AC'} · {connectorLabel(port.connectorType ?? 'STANDARD')}</small>
      {port.bookingStartAt && <small>Đang giữ: {formatDateTime(port.bookingStartAt)} – {formatTime(port.bookingEndAt ?? port.bookingStartAt)}</small>}
      {port.incidentDescription && <small className="port-incident-detail">{incidentLabel(port.incidentDescription)}</small>}
    </div><StatusBadge value={port.status} />
    <label className="admin-port-power">Tối đa kW<input aria-label={`Công suất tối đa trụ ${uppercasePortName(port.displayName)}`} type="number" min={port.chargingMode === 'DC' ? 30 : 10} max={port.chargingMode === 'DC' ? 150 : 22} step="0.1" value={powerDrafts[port.portId] ?? String(port.maxPowerKw ?? (port.chargingMode === 'DC' ? 30 : 10))} onChange={event=>setPowerDrafts(current=>({...current,[port.portId]:event.target.value}))} /></label>
    <button className="button button-secondary button-small admin-port-save" disabled={Boolean(busyId) || Number(powerDrafts[port.portId] ?? port.maxPowerKw ?? 3)===Number(port.maxPowerKw ?? 3)} onClick={()=>savePower(port)}>{busyId===port.portId?'Đang lưu…':'Lưu công suất'}</button>
    {['AVAILABLE','DISABLED'].includes(port.status) && <button className="button button-quiet button-small admin-port-toggle" disabled={Boolean(busyId)} onClick={()=>toggle(port)}>{busyId===port.portId?'Đang lưu…':port.status==='DISABLED'?'Mở trụ':'Khóa / bảo trì trụ'}</button>}
  </div>)}</div>}</section>
  </>
}

function TariffsAdmin() {
  const { data, loading, error, retry, setData } = useRead(api.tariffs, 'admin-tariffs')
  const { notify } = useApp()
  const [draft, setDraft] = useState({ dayPrice: '3800', eveningPrice: '4500', dayStart: '06:00', dayEnd: '17:00', eveningStart: '17:00', eveningEnd: '22:00', grace: '15', fee: '1000' })
  const [busy, setBusy] = useState(false); const [formError, setFormError] = useState('')
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); const dayPrice=Number(draft.dayPrice), eveningPrice=Number(draft.eveningPrice), grace=Number(draft.grace), fee=Number(draft.fee)
    if(!Number.isFinite(dayPrice)||dayPrice<=0||!Number.isFinite(eveningPrice)||eveningPrice<=0||!Number.isInteger(grace)||grace<0||!Number.isFinite(fee)||fee<0||draft.dayStart>=draft.dayEnd||draft.dayEnd!==draft.eveningStart||draft.eveningStart>=draft.eveningEnd) { setFormError('Kiểm tra đơn giá, khung giờ liền nhau và quy định phí chiếm chỗ.'); return }
    setBusy(true); setFormError('')
    try {
      const value=await api.createTariff({ dayPricePerKwh:dayPrice,eveningPricePerKwh:eveningPrice,dayStart:draft.dayStart,dayEnd:draft.dayEnd,eveningStart:draft.eveningStart,eveningEnd:draft.eveningEnd,occupancyGraceMinutes:grace,occupancyFeePerMinute:fee })
      setData([value,...(data??[])]); notify({kind:'success',text:'Đã lưu biểu giá và quy định chiếm chỗ.'})
    } catch(reason) { setFormError(messageFor(reason)) } finally { setBusy(false) }
  }
  const field = (key: keyof typeof draft,label:string,type='number',min?:string,step?:string) => <Field label={label} type={type} min={min} step={step} value={draft[key]} onChange={event=>setDraft(current=>({...current,[key]:event.target.value}))} />
  return <><PageHeader eyebrow="QUẢN TRỊ HỆ THỐNG" title="Biểu giá" detail="Giá phiên được chốt theo khung giờ tại lúc bắt đầu sạc." />{formError && <FormError message={formError} />}
    <section className="panel panel-spacious"><div className="panel-heading"><div><p className="eyebrow">CẤU HÌNH GIÁ VÀ PHÍ</p><h2>Thêm biểu giá</h2></div></div><form noValidate className="tariff-admin-form" onSubmit={submit}>
      <div className="tariff-time-row"><div className="tariff-window-heading"><strong>Ban ngày</strong><span>Giá áp dụng theo giờ Việt Nam</span></div>{field('dayStart','Từ','time')}{field('dayEnd','Đến','time')}{field('dayPrice','Đơn giá (đ/kWh)','number','1','100')}</div>
      <div className="tariff-time-row"><div className="tariff-window-heading"><strong>Buổi tối</strong><span>Giá áp dụng theo giờ Việt Nam</span></div>{field('eveningStart','Từ','time')}{field('eveningEnd','Đến','time')}{field('eveningPrice','Đơn giá (đ/kWh)','number','1','100')}</div>
      <div className="tariff-time-row tariff-occupancy-row"><div className="tariff-window-heading"><strong>Chiếm chỗ</strong><span>Thiết lập quy định hiển thị cho khách hàng</span></div>{field('grace','Thời gian miễn phí (phút)','number','0','1')}{field('fee','Sau đó (đ/phút)','number','0','100') }<ActionButton type="submit" busy={busy} className="button-primary"><CreditCard size={16} />Lưu biểu giá</ActionButton></div>
      <p className="form-hint">Mặc định: 06:00–17:00 là 3.800 đ/kWh; 17:00–22:00 là 4.500 đ/kWh; ngoài giờ dùng giá buổi tối. Miễn phí chiếm chỗ 15 phút, sau đó 1.000 đ/phút. Mức phí này chưa tự động tính cho đến khi thiết bị báo thời điểm xe rời cổng.</p>
    </form></section>
    <section className="panel panel-spacious"><div className="panel-heading"><div><p className="eyebrow">LỊCH SỬ ÁP DỤNG</p><h2>Biểu giá đã lưu</h2></div></div>{loading ? <Loading label="Đang tải biểu giá" /> : error ? <LoadError onRetry={retry} /> : !data?.length ? <EmptyState title="Chưa có biểu giá" detail="Thêm biểu giá để khách hàng xem giá và bắt đầu phiên sạc." /> : <DataTable label="Danh sách biểu giá"><thead><tr><th>Khung giá 1</th><th>Khung giá 2</th><th>Chiếm chỗ</th><th>Hiệu lực</th></tr></thead><tbody>{data.map(item=><tr key={item.tariffId}><td><strong>{item.dayPricePerKwh==null?`${formatVnd(item.pricePerKwh)} / kWh`: `${timeLabel(item.dayStart)}–${timeLabel(item.dayEnd)} · ${formatVnd(item.dayPricePerKwh)} / kWh`}</strong></td><td><strong>{item.eveningPricePerKwh==null?'—':`${timeLabel(item.eveningStart)}–${timeLabel(item.eveningEnd)} · ${formatVnd(item.eveningPricePerKwh)} / kWh`}</strong></td><td>{item.occupancyGraceMinutes} phút, sau đó {formatVnd(item.occupancyFeePerMinute)}/phút</td><td>{formatDateTime(item.effectiveAt)} · <StatusBadge value={item.active?'AVAILABLE':'DISABLED'} /></td></tr>)}</tbody></DataTable>}</section>
  </>
}

function ReportsAdmin() {
  const { data, loading, error, retry } = useRead(api.report, 'admin-report')
  return <><PageHeader eyebrow="TỔNG HỢP BỆNH VIỆN" title="Báo cáo vận hành" detail="Số liệu từ các phiên hoàn tất và giao dịch đã xác nhận." />
    {loading ? <Loading label="Đang tổng hợp báo cáo" /> : error ? <LoadError onRetry={retry} /> : data && <><div className="metric-row"><Metric icon={<BatteryCharging size={18} />} label="Phiên hoàn tất" value={String(data.completedSessions)} /><Metric icon={<Gauge size={18} />} label="Điện năng mô phỏng" value={`${formatNumber(data.totalEnergyKwh)} kWh`} /><Metric icon={<CreditCard size={18} />} label="Phí mô phỏng" value={formatVnd(data.totalFeeVnd)} /><Metric icon={<AlertTriangle size={18} />} label="Sự cố chưa xử lý" value={String(data.unresolvedIncidents)} /></div><section className="panel report-payment"><div className="report-payment-mark"><CreditCard size={20} /></div><div><p className="eyebrow">GIAO DỊCH ĐÃ XÁC NHẬN</p><h2>{formatVnd(data.paidVnd)}</h2></div></section></>}
  </>
}

function Metric({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) { return <article className="metric-card"><span className="metric-icon">{icon}</span><span className="metric-label">{label}</span><strong>{value}</strong></article> }
function statusLabel(value: string) { const labels: Record<string, string> = { AVAILABLE: 'Sẵn sàng', RESERVED: 'Đã giữ chỗ', CHARGING: 'Đang sạc', FAULTED: 'Có sự cố', OFFLINE: 'Mất kết nối', DISABLED: 'Đang khóa', ACKNOWLEDGED: 'Đã xác nhận', ASSIGNED: 'Đã phân công', REPAIR_REPORTED: 'Nhân viên báo đã sửa', PENDING: 'Đang chờ' }; return labels[value] ?? 'Chưa xác định' }
function formatVnd(value: number) { return new Intl.NumberFormat('vi-VN', { style: 'currency', currency: 'VND', maximumFractionDigits: 0 }).format(value) }
function formatNumber(value: number) { return new Intl.NumberFormat('vi-VN', { maximumFractionDigits: 2 }).format(value) }
function formatDate(value: string) { return new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', day: '2-digit', month: 'short', year: 'numeric' }).format(new Date(value)) }
function formatTime(value: string) { return new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) }
function formatDateTime(value: string) { return new Intl.DateTimeFormat('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh', day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) }
function timeLabel(value?: string | null) { return value ? value.slice(0,5) : '--:--' }
function bookingStartReady(booking: Booking) { const now = Date.now(); const start = new Date(booking.startAt).getTime(); return now >= start && now < start + 15 * 60 * 1000 }
