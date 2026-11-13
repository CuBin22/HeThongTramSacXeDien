export type Role = 'USER' | 'ADMIN'
export interface User { userId: string; email: string; role: Role; enabled: boolean }
export interface Station { stationId: string; name: string; active: boolean; connectorType: string }
export interface Port {
  portId: string; stationId: string; displayName?: string; connectorType?: string; chargingMode?: 'AC' | 'DC'; status: string; deviceStatus: string; bookingId: string | null; sessionId: string | null; lastSeenAt: string | null
  maxPowerKw?: number; bookingStartAt?: string | null; bookingEndAt?: string | null; nextBookingStartAt?: string | null; nextBookingEndAt?: string | null
  incidentDescription?: string | null; incidentStatus?: string | null
}
export interface Booking { bookingId: string; userId: string; stationId: string; portId: string; startAt: string; endAt: string; status: string; vehicleId?: string | null }
export interface ChargingSession {
  sessionId: string; bookingId: string; portId: string; portName?: string; status: string; initialBatteryPercent: number; batteryPercent: number
  energyKwh: number; powerKw?: number | null; pricePerKwh: number; totalFee: number | null; startedAt: string; chargingStartedAt?: string | null; endedAt: string | null
  deviceStatus: string; completionReason?: 'DEVICE_ACK' | 'DEVICE_DISCONNECTED' | null; nearFull: boolean; commandId: string | null; commandStatus: string | null; deliveryPending: boolean; commandReason: string | null
  vehicleId?: string | null; batteryCapacityKwh: number; targetType?: 'FULL' | 'BATTERY_PERCENT' | 'BUDGET'; targetBatteryPercent?: number | null; targetBudgetVnd?: number | null
  energyFeeVnd?: number | null; occupancyFeeVnd?: number | null
  amountDueVnd?: number | null; externalPaidVnd?: number | null; remainingDueVnd?: number | null; paymentStatus?: string | null; paymentRedirectUrl?: string | null
}
export interface Payment { paymentId: string; sessionId: string; reference: string; amountVnd: number; status: string; providerTransactionId: string | null; redirectUrl: string | null }
export interface Incident { incidentId: string; portId: string; sessionId: string | null; description: string; reportType: 'DEVICE' | 'CUSTOMER'; category: string | null; reporterUserId: string | null; reporterEmail: string | null; stationName: string; attachmentIds: string[]; status: string; reportedAt: string; acknowledgedBy: string | null; resolvedBy: string | null; resolvedAt: string | null; assignedStaffName: string | null; assignmentNote: string | null; assignedAt: string | null; repairReport: string | null; repairReportedAt: string | null; repairReportedBy: string | null; recoveryStatus: string | null }
export interface Recovery { recoveryId: string; stationId: string; portId: string; requestedAt: string; acknowledgedAt: string | null; status: string }
export interface Tariff { tariffId: string; pricePerKwh: number; dayPricePerKwh: number | null; eveningPricePerKwh: number | null; dayStart: string | null; dayEnd: string | null; eveningStart: string | null; eveningEnd: string | null; occupancyGraceMinutes: number; occupancyFeePerMinute: number; active: boolean; effectiveAt: string }
export interface CurrentTariff extends Omit<Tariff, 'tariffId' | 'active'> {}
export interface Vehicle { vehicleId: string; name: string; model: string; connectorType: string; batteryCapacityKwh: number; licensePlate?: string | null; productionYear?: number | null; maxChargingPowerKw?: number | null; isDefault: boolean }
export interface VehicleHistoryRow { sessionId: string; vehicleId: string; vehicleName: string; stationId: string; stationName: string; portId: string; startedAt: string; endedAt: string; energyKwh: number; totalCostVnd: number; externalPaidVnd: number; amountPaidVnd: number }
export interface VehicleHistory { sessions: VehicleHistoryRow[]; totalSessions: number; totalEnergyKwh: number; totalCostVnd: number; totalPaidVnd: number }
export interface ChargeQuote { pricePerKwh: number; estimatedEnergyKwh: number; estimatedCostVnd: number; batteryCapacityKwh: number; connectorType: string }
export interface ChargeOptions { vehicleId: string; initialBatteryPercent: number; targetType: 'FULL' | 'BATTERY_PERCENT' | 'BUDGET'; targetBatteryPercent: number | null; targetBudgetVnd: number | null }
export interface Report { completedSessions: number; totalEnergyKwh: number; totalFeeVnd: number; paidVnd: number; unresolvedIncidents: number }

export class ApiError extends Error {
  constructor(readonly status: number, message: string) { super(message); this.name = 'ApiError' }
}

let csrf: string | null = null
let csrfRequest: Promise<string> | null = null

async function getCsrf(): Promise<string> {
  if (csrf) return csrf
  if (!csrfRequest) csrfRequest = fetch('/api/auth/csrf', { credentials: 'include' })
    .then(async response => {
      if (!response.ok) throw new ApiError(response.status, 'Không thể xác thực yêu cầu')
      const data = await response.json() as { token: string }
      csrf = data.token
      return data.token
    }).finally(() => { csrfRequest = null })
  return csrfRequest
}

async function request<T>(path: string, init: RequestInit = {}, idempotencyKey?: string): Promise<T> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof URLSearchParams) && !(init.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  if (idempotencyKey) headers.set('Idempotency-Key', idempotencyKey)
  if (!['GET', 'HEAD', 'OPTIONS'].includes(method)) headers.set('X-CSRF-TOKEN', await getCsrf())
  const response = await fetch(path, { ...init, method, headers, credentials: 'include' })
  if (response.status === 204) return undefined as T
  const contentType = response.headers.get('content-type') ?? ''
  const body: unknown = contentType.includes('application/json') ? await response.json() : await response.text()
  if (!response.ok) {
    const message = typeof body === 'object' && body !== null && 'detail' in body ? String(body.detail)
      : typeof body === 'object' && body !== null && 'message' in body ? String(body.message) : `Yêu cầu thất bại (${response.status})`
    throw new ApiError(response.status, message)
  }
  return body as T
}

const json = (method: string, value?: unknown): RequestInit => ({ method, ...(value === undefined ? {} : { body: JSON.stringify(value) }) })

export const api = {
  me: () => request<User>('/api/auth/me'),
  login: async (email: string, password: string) => {
    await getCsrf()
    const result = await request<User>('/api/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ email, password }) })
    csrf = null
    await getCsrf()
    return result
  },
  register: (email: string, password: string) => request<User>('/api/auth/register', { ...json('POST', { email, password }) }),
  logout: async () => { const result = await request<void>('/api/auth/logout', { method: 'POST' }); csrf = null; return result },
  stations: () => request<Station[]>('/api/stations'),
  ports: (stationId: string) => request<Port[]>(`/api/stations/${encodeURIComponent(stationId)}/ports`),
  availablePorts: (stationId: string, vehicleId: string, startAt: string, endAt: string) => {
    const query = new URLSearchParams({ vehicleId, startAt, endAt })
    return request<Port[]>(`/api/stations/${encodeURIComponent(stationId)}/availability?${query}`)
  },
  currentTariff: (stationId: string) => request<CurrentTariff[]>(`/api/stations/${encodeURIComponent(stationId)}/tariff`),
  vehicles: () => request<Vehicle[]>('/api/vehicles'),
  createVehicle: (payload: Pick<Vehicle, 'name' | 'model' | 'connectorType' | 'batteryCapacityKwh'> & Partial<Pick<Vehicle, 'licensePlate' | 'productionYear' | 'maxChargingPowerKw'>>) => request<Vehicle>('/api/vehicles', json('POST', payload)),
  updateVehicle: (id: string, payload: Pick<Vehicle, 'name' | 'model' | 'connectorType' | 'batteryCapacityKwh'> & Partial<Pick<Vehicle, 'licensePlate' | 'productionYear' | 'maxChargingPowerKw'>>) => request<Vehicle>(`/api/vehicles/${encodeURIComponent(id)}`, json('PUT', payload)),
  makeDefaultVehicle: (id: string) => request<Vehicle>(`/api/vehicles/${encodeURIComponent(id)}/default`, { method: 'POST' }),
  vehicleHistory: (filters: { month: string; vehicleId: string; stationId: string }) => {
    const query = new URLSearchParams()
    if (filters.month) query.set('month', filters.month)
    if (filters.vehicleId) query.set('vehicleId', filters.vehicleId)
    if (filters.stationId) query.set('stationId', filters.stationId)
    return request<VehicleHistory>(`/api/vehicles/history?${query}`)
  },
  bookings: () => request<Booking[]>('/api/bookings'),
  reserve: (payload: { stationId: string; portId: string; vehicleId: string; startAt: string; endAt: string }, key: string) => request<Booking>('/api/bookings', { ...json('POST', payload) }, key),
  cancelBooking: (id: string, key: string) => request<Booking>(`/api/bookings/${encodeURIComponent(id)}/cancel`, { method: 'POST' }, key),
  quoteCharge: (bookingId: string, options: ChargeOptions) => request<ChargeQuote>(`/api/bookings/${encodeURIComponent(bookingId)}/quote`, json('POST', options)),
  start: (bookingId: string, options: ChargeOptions, key: string) => request<ChargingSession>(`/api/bookings/${encodeURIComponent(bookingId)}/start`, json('POST', options), key),
  sessions: () => request<ChargingSession[]>('/api/sessions'),
  session: (id: string) => request<ChargingSession>(`/api/sessions/${encodeURIComponent(id)}`),
  stop: (id: string, key: string) => request<ChargingSession>(`/api/sessions/${encodeURIComponent(id)}/stop`, { method: 'POST' }, key),
  startPayment: (sessionId: string, key: string) => request<{ paymentId: string; status: string; redirectUrl: string }>(`/api/sessions/${encodeURIComponent(sessionId)}/payments`, { method: 'POST' }, key),
  payments: () => request<Payment[]>('/api/payments'),
  payment: (id: string) => request<Payment>(`/api/payments/${encodeURIComponent(id)}`),
  paymentReturn: (signedFields: string) => request<{ paymentId: string; sessionId: string; status: string }>(`/api/payments/vnpay/return?${signedFields}`),
  incidents: () => request<Incident[]>('/api/incidents'),
  myIncidents: () => request<Incident[]>('/api/my/incidents'),
  reportIncident: (form: FormData, key: string) => request<Incident>('/api/incidents/report', { method: 'POST', body: form }, key),
  incidentAttachmentUrl: (incidentId: string, attachmentId: string, mine = false) => `${mine ? '/api/my/incidents' : '/api/incidents'}/${encodeURIComponent(incidentId)}/attachments/${encodeURIComponent(attachmentId)}`,
  assignIncident: (id: string, payload: { staffName: string; note: string }) => request<Incident>(`/api/incidents/${encodeURIComponent(id)}/assign`, json('POST', payload)),
  reportIncidentRepair: (id: string, payload: { report: string }) => request<Incident>(`/api/incidents/${encodeURIComponent(id)}/repair-reported`, json('POST', payload)),
  resolveIncident: (id: string) => request<Incident>(`/api/incidents/${encodeURIComponent(id)}/resolve`, { method: 'POST' }),
  recoverPort: (id: string) => request<Recovery>(`/api/admin/ports/${encodeURIComponent(id)}/recover`, { method: 'POST' }),
  users: () => request<User[]>('/api/admin/users'),
  updateUser: (id: string, payload: { role: Role; enabled: boolean }) => request<User>(`/api/admin/users/${encodeURIComponent(id)}`, { ...json('PUT', payload) }),
  tariffs: () => request<Tariff[]>('/api/admin/tariffs'),
  createTariff: (payload: { dayPricePerKwh: number; eveningPricePerKwh: number; dayStart: string; dayEnd: string; eveningStart: string; eveningEnd: string; occupancyGraceMinutes: number; occupancyFeePerMinute: number }) => request<Tariff>('/api/admin/tariffs', { ...json('POST', payload) }),
  report: () => request<Report>('/api/admin/reports'),
  chargers: () => request<Port[]>('/api/admin/chargers'),
  createCharger: (payload: { chargingMode: 'AC' | 'DC'; connectorType: string; maxPowerKw: number }) => request<Port>('/api/admin/chargers', { ...json('POST', payload) }),
  createStation: (payload: { name: string; connectorType: string }) => request<Station>('/api/admin/stations', { ...json('POST', payload) }),
  updateStation: (id: string, payload: { name: string; active: boolean }) => request<Station>(`/api/admin/stations/${encodeURIComponent(id)}`, { ...json('PUT', payload) }),
  createPort: (id: string, chargingMode: 'AC' | 'DC') => request<Port>(`/api/admin/stations/${encodeURIComponent(id)}/ports`, { ...json('POST', { chargingMode }) }),
  updatePort: (id: string, enabled: boolean) => request<Port>(`/api/admin/ports/${encodeURIComponent(id)}`, { ...json('PUT', { enabled }) }),
  updatePortPower: (id: string, maxPowerKw: number) => request<{ portId: string; maxPowerKw: number }>(`/api/admin/ports/${encodeURIComponent(id)}/power`, json('PUT', { maxPowerKw })),
}
