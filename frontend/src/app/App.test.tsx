import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { App } from './App'
import { ApiError, api } from '../api/client'

vi.mock('../api/client', async (importOriginal) => {
  const original = await importOriginal<typeof import('../api/client')>()
  return { ...original, api: {
    ...original.api,
    me: vi.fn(), login: vi.fn(), register: vi.fn(), logout: vi.fn(), stations: vi.fn(), ports: vi.fn(), bookings: vi.fn(), reserve: vi.fn(), cancelBooking: vi.fn(),
    sessions: vi.fn(), session: vi.fn(), start: vi.fn(), stop: vi.fn(), payment: vi.fn(), paymentReturn: vi.fn(), startPayment: vi.fn(), incidents: vi.fn(),
    assignIncident: vi.fn(), reportIncidentRepair: vi.fn(), resolveIncident: vi.fn(), recoverPort: vi.fn(), users: vi.fn(), updateUser: vi.fn(), tariffs: vi.fn(),
    createTariff: vi.fn(), report: vi.fn(), createStation: vi.fn(), updateStation: vi.fn(), createPort: vi.fn(), updatePort: vi.fn(),
  } }
})

const person = (role: 'USER' | 'ADMIN' = 'USER') => ({
  userId: 'user-1', email: 'minh@example.edu', role, enabled: true,
})
const station = { stationId: 'station-1', name: 'Bãi xe Bệnh viện', active: true, connectorType: 'TYPE_2' }
const booking = {
  bookingId: 'booking-1', userId: 'user-1', stationId: 'station-1', portId: 'port-auto',
  startAt: '2026-10-26T08:00:00+07:00', endAt: '2026-10-26T09:00:00+07:00', status: 'CONFIRMED',
}
const session = (overrides: Record<string, unknown> = {}) => ({
  sessionId: 'session-1', bookingId: 'booking-1', portId: 'port-auto', status: 'CHARGING',
  initialBatteryPercent: 28, batteryPercent: 90, batteryCapacityKwh: 3, energyKwh: 1.25, pricePerKwh: 3000,
  totalFee: null, startedAt: '2026-10-26T08:00:00Z', endedAt: null, deviceStatus: 'CHARGING', commandReason: null,
  nearFull: true, commandId: 'command-1', commandStatus: 'ACKNOWLEDGED', deliveryPending: false,
  ...overrides,
})
const mount = (path = '/', role: 'USER' | 'ADMIN' = 'USER', configure: () => void = () => undefined) => {
  vi.mocked(api.me).mockResolvedValue(person(role))
  vi.mocked(api.stations).mockResolvedValue([station])
  vi.mocked(api.ports).mockResolvedValue([])
  vi.mocked(api.bookings).mockResolvedValue([booking])
  vi.mocked(api.sessions).mockResolvedValue([])
  vi.mocked(api.session).mockResolvedValue(session())
  vi.mocked(api.incidents).mockResolvedValue([])
  vi.mocked(api.users).mockResolvedValue([])
  vi.mocked(api.tariffs).mockResolvedValue([])
  vi.mocked(api.report).mockResolvedValue({ completedSessions: 0, totalEnergyKwh: 0, totalFeeVnd: 0, paidVnd: 0, unresolvedIncidents: 0 })
  configure()
  window.history.replaceState({}, '', path)
  return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>)
}

describe('Hospital charging role workflows', () => {
  beforeEach(() => vi.clearAllMocks())
  afterEach(cleanup)

  it('shows login errors inline and lets users reveal their password', async () => {
    vi.mocked(api.me).mockRejectedValue(new ApiError(401, 'Unauthorized'))
    vi.mocked(api.login).mockRejectedValue(new Error('Invalid credentials'))
    render(<MemoryRouter><App /></MemoryRouter>)
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('Địa chỉ email'), 'minh@example.edu')
    await user.type(screen.getByLabelText('Mật khẩu'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Hiện mật khẩu' }))
    expect(screen.getByLabelText('Mật khẩu')).toHaveAttribute('type', 'text')
    await user.click(screen.getByRole('button', { name: 'Đăng nhập' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Email hoặc mật khẩu chưa chính xác')
  })

  it('shows only the user workspace and a dedicated 403 for a forbidden route', async () => {
    mount('/admin')
    expect(await screen.findByRole('heading', { name: 'Bạn chưa có quyền vào mục này' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Trạm sạc' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Quản trị' })).not.toBeInTheDocument()
  })

  it('reserves by station and time without asking the user to choose a port or battery target', async () => {
    vi.mocked(api.reserve).mockResolvedValue(booking)
    mount('/bookings/new')
    const user = userEvent.setup()
    await screen.findByRole('heading', { name: 'Đặt chỗ sạc' })
    await user.selectOptions(await screen.findByRole('combobox', { name: 'Trạm sạc' }), 'station-1')
    await user.type(screen.getByLabelText('Bắt đầu'), '2026-10-26T10:00')
    await user.type(screen.getByLabelText('Kết thúc'), '2026-10-26T11:00')
    await user.click(screen.getByRole('button', { name: 'Đặt chỗ' }))
    await waitFor(() => expect(api.reserve).toHaveBeenCalled())
    const [payload] = vi.mocked(api.reserve).mock.calls[0]
    expect(payload).toMatchObject({ stationId: 'station-1' })
    expect(payload).not.toHaveProperty('portId')
    expect(payload).not.toHaveProperty('targetBatteryPercent')
    expect(screen.getByText(/cổng đã được hệ thống tự phân bổ/i)).toBeInTheDocument()
  })

  it('cancels a booking with a retained idempotency key', async () => {
    vi.mocked(api.cancelBooking).mockRejectedValueOnce(new Error('network timeout')).mockResolvedValueOnce({ ...booking, status: 'CANCELLED' })
    mount('/bookings')
    const user = userEvent.setup()
    await screen.findByRole('button', { name: 'Hủy' })
    await user.click(screen.getByRole('button', { name: 'Hủy' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Hủy' }))
    await waitFor(() => expect(api.cancelBooking).toHaveBeenCalledTimes(2))
    const keys = vi.mocked(api.cancelBooking).mock.calls.map(call => call[1])
    expect(keys[0]).toBeTruthy()
    expect(keys[0]).toBe(keys[1])
  })

  it('starts from the current battery only and reports the 90% warning', async () => {
    vi.mocked(api.start).mockResolvedValue(session({ status: 'START_REQUESTED', batteryPercent: 28, nearFull: false }))
    vi.mocked(api.session).mockResolvedValue(session())
    mount('/bookings', 'USER', () => vi.mocked(api.session).mockResolvedValue(session()))
    const user = userEvent.setup()
    await screen.findByText('Bãi xe Bệnh viện')
    await user.click(screen.getByRole('button', { name: 'Bắt đầu sạc' }))
    const batteryField = await screen.findByLabelText('Pin hiện tại (%)')
    await user.clear(batteryField)
    await user.type(batteryField, '28')
    await user.click(screen.getByRole('button', { name: 'Bắt đầu sạc' }))
    await waitFor(() => expect(api.start).toHaveBeenCalledWith('booking-1', 28, expect.any(String)))
    expect(screen.queryByLabelText(/pin mục tiêu/i)).not.toBeInTheDocument()
    expect(await screen.findByText('Pin gần đầy')).toBeInTheDocument()
    expect(screen.getByRole('meter', { name: 'Mức pin' })).toHaveAttribute('aria-valuenow', '90')
  })

  it('does not call a stop request acknowledged while device confirmation is pending', async () => {
    mount('/sessions/session-1', 'USER', () => vi.mocked(api.session).mockResolvedValue(session({ status: 'STOP_REQUESTED', batteryPercent: 100, nearFull: true, commandStatus: 'PENDING', deliveryPending: true })))
    expect(await screen.findByText('Đang chờ xác nhận dừng từ thiết bị')).toBeInTheDocument()
    expect(screen.getAllByText(/100%/).length).toBeGreaterThan(0)
    expect(screen.queryByText('Phiên đã hoàn tất')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Dừng sạc' })).not.toBeInTheDocument()
  })

  it('requires explicit sandbox payment and displays the redirect only after API confirmation', async () => {
    vi.mocked(api.startPayment).mockResolvedValue({ paymentId: 'payment-1', status: 'PENDING', redirectUrl: 'https://sandbox.vnpayment.vn/pay?token=demo' })
    mount('/sessions', 'USER', () => vi.mocked(api.sessions).mockResolvedValue([session({ status: 'COMPLETED', batteryPercent: 100, totalFee: 3750, endedAt: '2026-10-26T09:00:00Z' })]))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Thanh toán sandbox' }))
    expect(await screen.findByRole('link', { name: 'Tiếp tục đến VNPay sandbox' })).toHaveAttribute('href', 'https://sandbox.vnpayment.vn/pay?token=demo')
    expect(screen.getByText(/Không thu tiền thật trong bản demo/)).toBeInTheDocument()
  })

  it('exposes incident management in the admin workspace', async () => {
    mount('/', 'ADMIN')
    expect(await screen.findByRole('link', { name: 'Sự cố' })).toBeInTheDocument()
    expect(await screen.findByRole('link', { name: 'Tài khoản' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Tài khoản' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Biểu giá' })).toBeInTheDocument()
  })

  it('alerts the admin when a new incident arrives without a page reload', async () => {
    const incident = { incidentId: 'incident-hot', portId: 'port-hot', sessionId: 'session-1', description: 'DEVICE_FAULT: OVERHEAT: measured 85 C; threshold 70.0 C', reportType: 'DEVICE' as const, category: null, reporterUserId: null, reporterEmail: null, stationName: 'Trạm thử nghiệm', attachmentIds: [], status: 'OPEN', reportedAt: '2026-10-27T08:00:00Z', acknowledgedBy: null, resolvedBy: null, resolvedAt: null, assignedStaffName: null, assignmentNote: null, assignedAt: null, repairReport: null, repairReportedAt: null, repairReportedBy: null, recoveryStatus: null }
    let incidentReads = 0
    mount('/incidents', 'ADMIN', () => {
      vi.mocked(api.incidents).mockImplementation(async () => ++incidentReads === 1 ? [] : [incident])
    })
    expect(await screen.findByRole('heading', { name: 'Quản lý sự cố' })).toBeInTheDocument()
    await waitFor(() => expect(api.incidents).toHaveBeenCalledTimes(2), { timeout: 8000 })
    expect(await screen.findByText('Có sự cố mới tại TRỤ SẠC: Thiết bị quá nhiệt: 85 °C (ngưỡng an toàn 70 °C)')).toBeInTheDocument()
  }, 15000)

  it('keeps incident resolution disabled until the device confirms recovery', async () => {
    const incident = { incidentId: 'incident-1', portId: 'port-1', sessionId: null, description: 'Nhiệt độ cao', reportType: 'DEVICE' as const, category: null, reporterUserId: null, reporterEmail: null, stationName: 'Trạm thử nghiệm', attachmentIds: [], status: 'REPAIR_REPORTED', reportedAt: '2026-10-26T08:00:00Z', acknowledgedBy: 'admin-1', resolvedBy: null, resolvedAt: null, assignedStaffName: 'Nhân viên A', assignmentNote: null, assignedAt: '2026-10-26T08:05:00Z', repairReport: 'Đã thay dây cảm biến', repairReportedAt: '2026-10-26T08:15:00Z', repairReportedBy: 'admin-1', recoveryStatus: 'PENDING' }
    const faultedPort = { portId: 'port-1', stationId: 'station-1', status: 'FAULTED', deviceStatus: 'FAULTED', bookingId: null, sessionId: null, lastSeenAt: '2026-10-26T08:00:00Z' }
    let reads = 0
    mount('/incidents', 'ADMIN', () => {
      vi.mocked(api.incidents).mockImplementation(async () => [{ ...incident, recoveryStatus: ++reads > 1 ? 'ACKNOWLEDGED' : 'PENDING' }])
      vi.mocked(api.ports).mockResolvedValue([faultedPort])
      vi.mocked(api.resolveIncident).mockResolvedValue({ ...incident, status: 'RESOLVED' })
    })
    const user = userEvent.setup()
    const resolve = await screen.findByRole('button', { name: 'Xác nhận' })
    expect(resolve).toBeDisabled()
    await waitFor(() => expect(resolve).toBeEnabled(), { timeout: 8000 })
    expect(screen.queryByRole('button', { name: /Kiểm tra thiết bị|Kiểm tra xác nhận/ })).not.toBeInTheDocument()
    await user.click(resolve)
    await waitFor(() => expect(api.resolveIncident).toHaveBeenCalledWith('incident-1'))
  })

  it('reuses the same payment idempotency key after an uncertain network failure', async () => {
    vi.mocked(api.startPayment).mockRejectedValueOnce(new Error('network timeout')).mockResolvedValueOnce({ paymentId: 'payment-1', status: 'PENDING', redirectUrl: 'https://sandbox.vnpayment.vn/pay?token=demo' })
    mount('/sessions', 'USER', () => vi.mocked(api.sessions).mockResolvedValue([session({ status: 'COMPLETED', totalFee: 3750 })]))
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: 'Thanh toán sandbox' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Thanh toán sandbox' }))
    await screen.findByRole('link', { name: 'Tiếp tục đến VNPay sandbox' })
    const keys = vi.mocked(api.startPayment).mock.calls.map(call => call[1])
    expect(keys[0]).toBe(keys[1])
  })

  it('shows a loading state and an actionable retry state when session reads fail', async () => {
    let rejectRead: (reason: Error) => void = () => {}
    vi.mocked(api.sessions).mockReturnValueOnce(new Promise((_resolve, reject) => { rejectRead = reject })).mockResolvedValueOnce([session()])
    mount('/sessions')
    expect(await screen.findByText('Đang tải phiên sạc')).toBeInTheDocument()
    rejectRead(new Error('network unavailable'))
    const retry = await screen.findByRole('button', { name: 'Tải lại' })
    fireEvent.click(retry)
    expect(await screen.findByText('Đang sạc')).toBeInTheDocument()
  })
})
