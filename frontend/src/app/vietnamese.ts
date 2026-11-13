/** Translate presentation only; protocol and stored incident codes remain unchanged. */
export function incidentLabel(description: string): string {
  if (description.includes('HEARTBEAT_TIMEOUT')) return 'Mất kết nối thiết bị';
  if (description.includes('OVERHEAT')) {
    const measured = description.match(/measured ([\d.]+) C/);
    const threshold = description.match(/threshold ([\d.]+) C/);
    return 'Thiết bị quá nhiệt' + (measured ? `: ${Number(measured[1]).toLocaleString('vi-VN')} °C` : '') + (threshold ? ` (ngưỡng an toàn ${Number(threshold[1]).toLocaleString('vi-VN')} °C)` : '');
  }
  if (description.includes('SIMULATOR_RESTARTED_WHILE_CHARGING')) return 'Thiết bị khởi động lại khi đang sạc';
  if (description.includes('SIMULATOR_FAULT_ACTIVE')) return 'Thiết bị vẫn còn sự cố, cần nhân viên xử lý';
  if (description.includes('COMMAND_REJECTED')) return 'Thiết bị từ chối yêu cầu điều khiển; cần kiểm tra cổng sạc';
  const text = description.replace(/^DEVICE_FAULT:\s*/, '');
  return /[À-ỹ]/.test(text) ? text : 'Sự cố thiết bị, cần nhân viên kiểm tra';
}
export function connectorLabel(value: string): string {
  return ({ STANDARD: 'Chuẩn tiêu chuẩn', TYPE_2: 'Type 2', GB_T: 'GB/T', CCS2: 'CCS2', CHADEMO: 'CHAdeMO' } as Record<string, string>)[value] ?? 'Chuẩn cổng khác';
}

export function incidentCategoryLabel(value: string | null | undefined): string {
  return ({
    START_CHARGE_FAILED: 'Không bắt đầu sạc được', GUN_STUCK: 'Súng sạc bị kẹt',
    INCORRECT_BILLING: 'Tính tiền chưa chính xác', DISPLAY_FAILURE: 'Trụ hoặc màn hình không hoạt động',
    STOP_CHARGE_FAILED: 'Không thể kết thúc phiên sạc', OTHER: 'Lỗi khác',
  } as Record<string, string>)[value ?? ''] ?? 'Sự cố thiết bị'
}
