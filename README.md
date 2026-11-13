# Hệ thống trạm sạc xe điện

Campus Charge là ứng dụng mô phỏng quản lý trạm sạc xe máy điện tại bệnh viện. Người dùng có thể quản lý phương tiện, xem trạm và cổng sạc, đặt chỗ, theo dõi phiên sạc và lịch sử. Quản trị viên quản lý trạm, cổng sạc, tài khoản, biểu giá và sự cố.

Project gồm giao diện React, backend Spring Boot, cơ sở dữ liệu PostgreSQL, MQTT broker Mosquitto và bộ mô phỏng thiết bị sạc. Bộ mô phỏng giúp trình diễn giao tiếp IoT mà không cần trụ sạc vật lý.

## Cài đặt và chạy

### Yêu cầu

- Docker Desktop có Docker Compose, đã khởi động.
- PowerShell 7.
- Kết nối Internet để tải Docker image và các thư viện khi build lần đầu.

Tải project từ GitHub bằng **Code → Download ZIP** rồi giải nén, hoặc dùng Git:

```powershell
git clone https://github.com/CuBin22/HeThongTramSacXeDien.git
cd HeThongTramSacXeDien
```

Mở PowerShell tại thư mục gốc project và chạy:

```powershell
Copy-Item .env.example .env
docker compose up --build -d
docker compose ps
```

Chờ backend sẵn sàng, sau đó tạo dữ liệu demo:

```powershell
./scripts/seed-demo.ps1
```

Lệnh này tạo tài khoản, trạm, cổng sạc và biểu giá mẫu. Nếu backend chưa sẵn sàng, đợi thêm rồi chạy lại lệnh. PostgreSQL được chạy trong Docker; backend tự tạo cấu trúc database bằng Flyway. Dữ liệu được lưu trong Docker volume.

Mở ứng dụng tại [http://localhost:5173](http://localhost:5173).

### Tài khoản demo

| Vai trò | Email | Mật khẩu |
|---|---|---|
| Quản trị viên | `admin@campus.local` | `DemoAdmin123!` |
| Người dùng | `student@campus.local` | `DemoStudent123!` |

Đây là tài khoản chỉ dùng cho demo cục bộ. Không đưa `.env`, khóa hoặc thông tin xác thực thực tế lên GitHub.

### Bật thanh toán VNPay sandbox

Thanh toán mặc định bị tắt. Để thử sandbox, sửa `.env`: đặt `PAYMENT_PROVIDER=vnpay`, điền `VNPAY_TMN_CODE` và `VNPAY_HASH_SECRET` do VNPay cấp, đồng thời kiểm tra `VNPAY_URL` và `VNPAY_RETURN_URL`. Sau đó tạo lại backend để nhận cấu hình mới:

```powershell
docker compose up -d --force-recreate backend
```

## Demo IoT

Các kịch bản dưới đây dùng **thiết bị mô phỏng**, không điều khiển phần cứng thật. Backend và simulator trao đổi lệnh, trạng thái và dữ liệu sạc qua MQTT; PostgreSQL lưu trạng thái và sự cố.

### 1. Phiên sạc bình thường

1. Đăng nhập bằng tài khoản người dùng, tạo hoặc chọn phương tiện.
2. Xem trạm, chọn cổng khả dụng và đặt chỗ; bắt đầu sạc theo hướng dẫn trên giao diện.
3. Mở phần theo dõi phiên sạc để quan sát phần trăm pin và điện năng thay đổi theo dữ liệu mô phỏng.
4. Dừng phiên sạc và quan sát trạng thái cùng thông tin phiên đã kết thúc.

Tên cổng hiển thị trên giao diện, ví dụ `AC-03-TYPE2`. Với các lệnh bên dưới, thay tên ví dụ bằng tên cổng thực tế trong trạm demo.

### 2. Mô phỏng quá nhiệt

Bắt đầu một phiên sạc trên cổng cần thử, sau đó mở PowerShell tại thư mục gốc project:

```powershell
./scripts/demo-overheat.ps1 -PortName "AC-03-TYPE2"
```

Script gửi sự kiện nhiệt độ mô phỏng 85°C và chờ xác nhận. Khi thành công, cổng chuyển sang trạng thái lỗi, sự cố được ghi nhận và phiên sạc dừng. Đăng nhập quản trị viên, vào **Sự cố** để xem và xử lý. Có thể bỏ `-PortName` để chọn phiên sạc đang hoạt động gần nhất.

### 3. Mô phỏng mất kết nối và kết nối lại

Chọn tên cổng có trên giao diện rồi chạy:

```powershell
./scripts/demo-disconnect.ps1 -PortName "AC-03-TYPE2"
```

Script chờ hệ thống xác nhận cổng mất kết nối và ghi nhận sự cố. Để mô phỏng kết nối trở lại:

```powershell
./scripts/demo-disconnect.ps1 -PortName "AC-03-TYPE2" -Restore
```

Sau khi kết nối lại, quản trị viên cần xử lý sự cố, kiểm tra thiết bị và xác nhận mở lại cổng trên giao diện quản trị. Nếu đang có phiên sạc, theo dõi thông báo và trạng thái phiên trên giao diện.

### Kiểm tra khi gặp lỗi

```powershell
docker compose ps
docker compose logs --tail 100 backend simulator mqtt
```

Kiểm tra Docker Desktop đang chạy, các dịch vụ đã sẵn sàng và tên cổng đúng như trên giao diện. Nếu cổng chưa khả dụng sau khi tạo dữ liệu, chờ simulator gửi heartbeat rồi tải lại trang.

## Dừng và chạy lại

```powershell
docker compose down
docker compose up -d
```

Lệnh `down` giữ lại dữ liệu trong volume. Để xóa toàn bộ dữ liệu PostgreSQL và trạng thái simulator, dùng `docker compose down -v`; lần chạy sau cần tạo lại dữ liệu demo.

## Cấu trúc project

- `backend/`: mã nguồn Spring Boot, migration và kiểm thử.
- `frontend/`: giao diện React và kiểm thử.
- `database/schema/`: các tệp SQL được chia theo loại đối tượng để tham khảo.
- `mosquitto/`: cấu hình MQTT broker.
- `scripts/`: tạo dữ liệu mẫu và chạy các kịch bản IoT.
- `ThietKeHeThong/`: biểu mẫu, sơ đồ và tệp thiết kế giao diện.
- `compose.yaml`, `.env.example`: cấu hình chạy demo bằng Docker Compose.
