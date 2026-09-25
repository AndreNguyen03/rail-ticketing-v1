# 07 — Kubernetes (Stage 10)

> Tài liệu này là tài liệu tham khảo cho Stage 10. Đọc [05 — Build Progression](05-build-progression.md)
> để hiểu *tại sao* Stage 10 xuất hiện ở thời điểm này trong roadmap.

---

## 1. Tại sao Kubernetes, tại sao bây giờ

Sau Stage 9, hệ thống có **9 services** cùng chạy. `docker-compose.yaml` đã dài 400+ dòng
và không thể trả lời những câu hỏi quan trọng:

- Nếu `inventory-service` crash lúc 3 giờ sáng, ai restart nó?
- Khi traffic tăng gấp 10 lần, làm sao scale chỉ `inventory-service` mà không động đến
  các service khác?
- Làm sao rolling update một service mà không có downtime?
- Nếu một node server bị mất điện, workload tự chuyển sang node khác như thế nào?

Docker Compose không trả lời được. Kubernetes thì có.

**Entry condition trong docs**: *"Compose starts to strain at ≥6 services"* — đã vượt ngưỡng.

---

## 2. Kubernetes là gì — kiến trúc tổng quan

Kubernetes (K8s) là một **container orchestrator**: nó quản lý vòng đời của container
trên nhiều máy chủ (nodes), tự động restart khi crash, tự động scale khi cần, tự
load-balance traffic.

### 2.1 Control Plane vs Data Plane

```
┌──────────────────────────────────────────────────────────┐
│                    CONTROL PLANE (brain)                  │
│                                                           │
│  ┌─────────────┐  ┌────────┐  ┌────────────────────────┐ │
│  │  API Server │  │  etcd  │  │  Controller Manager    │ │
│  │             │  │        │  │  • Deployment ctrl     │ │
│  │  Mọi lệnh   │  │ Source │  │  • HPA ctrl            │ │
│  │  kubectl    │  │  of    │  │  • Service ctrl        │ │
│  │  đi qua đây │  │ truth  │  │  (reconcile loops)     │ │
│  └──────┬──────┘  └────────┘  └────────────────────────┘ │
│         │                      ┌──────────────────────┐   │
│         │                      │  Scheduler           │   │
│         │                      │  "Pod này chạy       │   │
│         │                      │   trên Node nào?"    │   │
│         │                      └──────────────────────┘   │
└─────────┼────────────────────────────────────────────────┘
          │  kubelet (agent trên mỗi node)
┌─────────┼────────────────────────────────────────────────┐
│         │          DATA PLANE (workers)                   │
│  ┌──────▼───────────────────────────────────────────┐    │
│  │                    Node 1                         │    │
│  │  kubelet   ← nhận lệnh từ API Server              │    │
│  │  kube-proxy ← quản lý network rules (iptables)    │    │
│  │                                                   │    │
│  │  ┌──────────────┐  ┌──────────────┐               │    │
│  │  │     Pod      │  │     Pod      │               │    │
│  │  │ ┌──────────┐ │  │ ┌──────────┐ │               │    │
│  │  │ │inventory │ │  │ │ booking  │ │               │    │
│  │  │ │-service  │ │  │ │-service  │ │               │    │
│  │  │ └──────────┘ │  │ └──────────┘ │               │    │
│  │  └──────────────┘  └──────────────┘               │    │
│  └───────────────────────────────────────────────────┘    │
│  ┌───────────────────────────────────────────────────┐    │
│  │                    Node 2                         │    │
│  └───────────────────────────────────────────────────┘    │
└────────────────────────────────────────────────────────────┘
```

**etcd** là database key-value lưu toàn bộ state của cluster. Nếu etcd mất, cluster
mất đầu não. Backup etcd = backup cluster.

**Controller Manager** chạy các *reconcile loops*: liên tục so sánh "desired state"
(manifest YAML) với "current state" (thực tế đang chạy) và hành động để đưa về
desired state. Đây là triết lý cốt lõi của K8s: **declarative, not imperative**.

```
Bạn nói: "Tôi muốn 3 bản inventory-service"     ← desired state
K8s thấy: "Đang có 1 bản"                         ← current state
K8s làm:  Tạo thêm 2 bản                           ← reconciliation
```

### 2.2 Pod — đơn vị nhỏ nhất

Pod là một hoặc nhiều container **chạy cùng nhau**, chia sẻ network namespace và volume.
Thường 1 pod = 1 container (như repo này).

Pod **không bền vững** — khi node crash, pod đó mất. Deployment lo việc tạo pod mới.
Vì vậy không bao giờ dùng IP của pod để giao tiếp — dùng Service.

### 2.3 Service — stable endpoint

Service là virtual IP cố định + DNS name, route traffic đến các pod đang healthy.

```
booking-service (Pod, IP thay đổi khi restart)
       ↑
Service "booking-service" (ClusterIP 10.96.x.x:8083, không bao giờ đổi)
       ↑
inventory-service gọi: http://booking-service:8083
```

**Điều kỳ diệu**: `kube-proxy` trên mỗi node dùng iptables/eBPF để forward packet
từ ClusterIP → Pod IP thực sự, hoàn toàn transparent.

Ba loại Service:

| Type | Dùng khi | Trong project này |
|------|----------|-------------------|
| `ClusterIP` | Chỉ giao tiếp nội bộ | Tất cả services |
| `NodePort` | Expose ra ngoài qua port cố định | Dev/debug |
| `LoadBalancer` | Cloud production | Không dùng local |

Với k3d: Ingress thay thế LoadBalancer.

---

## 3. Các object K8s cần nắm cho project này

### 3.1 Deployment

Tương đương `service:` trong Compose. Khai báo *desired state* của một workload.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: inventory-service
  namespace: rail-ticketing
spec:
  replicas: 2                    # ← đây là "desired state"
  selector:
    matchLabels:
      app: inventory-service
  template:                      # ← Pod template
    metadata:
      labels:
        app: inventory-service   # ← Service dùng label này để route
    spec:
      containers:
        - name: inventory-service
          image: rail-ticketing-inventory-service:latest
          imagePullPolicy: Never # ← local image, không pull từ Docker Hub
          ports:
            - containerPort: 8082
          env:
            - name: DB_HOST
              value: postgres    # ← DNS của Service "postgres"
            - name: DB_PASSWORD
              valueFrom:
                secretKeyRef:   # ← đọc từ Secret, không hardcode
                  name: postgres-secret
                  key: inventory-password
          readinessProbe:        # ← thay thế depends_on + healthcheck
            httpGet:
              path: /actuator/health/readiness
              port: 8082
            initialDelaySeconds: 20
            periodSeconds: 5
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8082
            initialDelaySeconds: 60
            periodSeconds: 15
          resources:             # ← bắt buộc để HPA hoạt động
            requests:
              cpu: 200m          # 0.2 CPU core
              memory: 256Mi
            limits:
              cpu: 1000m
              memory: 512Mi
```

### 3.2 ConfigMap vs Secret

**ConfigMap** — non-sensitive config:
```yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
  namespace: rail-ticketing
data:
  INVENTORY_URL: "http://inventory-service:8082"
  KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
  OTEL_EXPORTER_OTLP_ENDPOINT: "http://jaeger:4318"
```

**Secret** — passwords, credentials:
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: postgres-secret
  namespace: rail-ticketing
type: Opaque
stringData:                      # K8s tự base64 encode
  postgres-password: postgres
  schedule-password: schedule_pw
  inventory-password: inventory_pw
  booking-password: booking_pw
  quota-password: quota_pw
```

> **Lưu ý**: K8s Secret chỉ base64, không encrypt. Trong production dùng
> Vault hoặc Sealed Secrets. Với dev/learning thì đủ dùng.

### 3.3 Liveness vs Readiness Probe

Đây là điểm **quan trọng nhất** khi migrate từ Compose.

```
livenessProbe:   "Container còn sống không?"
                 → FAIL: kubelet kill pod và restart

readinessProbe:  "Container sẵn sàng nhận traffic không?"
                 → FAIL: Service bỏ pod ra khỏi endpoint list
                         (pod vẫn sống, chỉ tạm không nhận traffic)
```

Với Spring Boot, actuator cung cấp sẵn:
- `/actuator/health/liveness` — JVM alive? (luôn UP trừ khi process crash)
- `/actuator/health/readiness` — DB connected? Redis connected? Kafka connected?

```yaml
# application.yml — phải bật
management:
  endpoint:
    health:
      probes:
        enabled: true
      show-details: always
```

### 3.4 PersistentVolumeClaim (PVC) cho PostgreSQL

```
PersistentVolume (PV)       ← "Miếng disk thực sự" (tạo bởi admin/cloud)
PersistentVolumeClaim (PVC) ← "Tôi cần 1Gi storage" (tạo bởi developer)
StorageClass                ← "Loại disk nào: SSD? NFS? local-path?"
```

Với k3d: StorageClass `local-path` tự tạo PV từ `/var/lib/rancher/k3s/storage/`
trên node → đủ cho dev, không dùng production.

```yaml
# pvc.yaml
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: postgres-pvc
  namespace: rail-ticketing
spec:
  accessModes:
    - ReadWriteOnce          # chỉ 1 node read/write cùng lúc
  storageClassName: local-path
  resources:
    requests:
      storage: 1Gi
```

```yaml
# deployment.yaml — mount PVC vào postgres container
volumes:
  - name: pgdata
    persistentVolumeClaim:
      claimName: postgres-pvc
containers:
  - name: postgres
    volumeMounts:
      - name: pgdata
        mountPath: /var/lib/postgresql/data
```

### 3.5 Ingress — entry point duy nhất từ ngoài vào

```
Client (browser/curl)
        │
        │ http://localhost:8080/api/v1/...
        ▼
Ingress Controller (Traefik, built-in trong k3d)
        │ match rules
        ▼
Service "api-gateway":8080 (ClusterIP)
        │
        ▼
Pods của api-gateway
```

```yaml
# ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: rt-ingress
  namespace: rail-ticketing
  annotations:
    traefik.ingress.kubernetes.io/router.entrypoints: web
spec:
  rules:
    - http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: api-gateway
                port:
                  number: 8080
```

### 3.6 HorizontalPodAutoscaler (HPA)

HPA là lý do số 1 để migrate sang K8s trong project này. `inventory-service` là
contention point — khi ticket sale bắt đầu, CPU tăng đột ngột.

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: inventory-hpa
  namespace: rail-ticketing
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: inventory-service
  minReplicas: 1
  maxReplicas: 6
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: 70  # scale up khi CPU > 70%
```

> **Nhớ từ Stage 2**: thêm pod vào inventory-service trên PostgreSQL path làm
> throughput *giảm* (USL curve). Sau Stage 4 với Redis Lua path, scale ngang
> mới thực sự có ý nghĩa. HPA ở Stage 10 khai thác đúng điều này.

---

## 4. k3d — Kubernetes chạy trong Docker

**k3s** là bản Kubernetes rút gọn (< 100MB binary), production-ready, dùng cho
edge và IoT. **k3d** là wrapper chạy k3s bên trong Docker containers — không cần VM.

```
k3d cluster create rt-cluster
         ↓
Docker tạo 2 containers:
  k3d-rt-cluster-server-0   ← control plane (API Server, etcd, scheduler)
  k3d-rt-cluster-agent-0    ← data plane node (chạy pods)
         ↓
kubectl tự cấu hình context "k3d-rt-cluster"
```

### 4.1 Tại sao không dùng minikube hay kind?

| Tool | Pros | Cons |
|------|------|------|
| **k3d** ✅ | Nhẹ, nhanh, production-close | Ít docs hơn |
| minikube | Docs nhiều, dễ bắt đầu | Nặng, slow startup |
| kind | CI-friendly | Phức tạp network |
| Docker Desktop K8s | Built-in | Chậm, resource heavy |

k3d phù hợp nhất cho project này: giống production hơn minikube, nhẹ hơn Docker Desktop.

### 4.2 Cài đặt

```powershell
# k3d (bao gồm kubectl)
winget install k3d

# Hoặc qua Chocolatey
choco install k3d

# Verify
k3d version     # k3d version v5.x.x
kubectl version --client
```

### 4.3 Tạo cluster

```powershell
k3d cluster create rt-cluster `
  --api-port 6550 `
  --port "8080:80@loadbalancer" `
  --port "8180:8180@loadbalancer" `
  --agents 1

# Giải thích:
# --api-port 6550        ← kubectl connect qua port này
# --port "8080:80@lb"    ← host:8080 → Ingress LoadBalancer:80
# --port "8180:8180@lb"  ← host:8180 → Keycloak (raw NodePort)
# --agents 1             ← 1 worker node (ngoài server node)
```

```powershell
# Verify cluster đang chạy
kubectl get nodes
# NAME                       STATUS   ROLES
# k3d-rt-cluster-server-0   Ready    control-plane,master
# k3d-rt-cluster-agent-0    Ready    <none>
```

### 4.4 Load images vào cluster

k3d không tự pull images từ local Docker daemon — phải import thủ công:

```powershell
# Build tất cả images bằng docker compose
docker compose build

# Import từng image vào k3d cluster
$images = @(
  "rail-ticketing-api-gateway",
  "rail-ticketing-schedule-service",
  "rail-ticketing-inventory-service",
  "rail-ticketing-booking-service",
  "rail-ticketing-payment-service",
  "rail-ticketing-waiting-room",
  "rail-ticketing-quota-service",
  "rail-ticketing-fare-service"
)

foreach ($img in $images) {
  k3d image import "${img}:latest" -c rt-cluster
  Write-Host "Imported $img"
}
```

> Sau mỗi lần rebuild service, phải re-import image và restart Deployment:
> ```powershell
> docker compose build booking-service
> k3d image import rail-ticketing-booking-service:latest -c rt-cluster
> kubectl -n rail-ticketing rollout restart deployment/booking-service
> ```

---

## 5. Cấu trúc manifest files

```
k8s/
├── namespace.yaml
├── kustomization.yaml           # kubectl apply -k k8s/ để apply tất cả
│
├── infra/                       # Infrastructure services
│   ├── postgres/
│   │   ├── secret.yaml          # DB users & passwords
│   │   ├── configmap.yaml       # init SQL (01-databases.sql)
│   │   ├── pvc.yaml
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── redis/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── kafka/
│   │   ├── configmap.yaml       # KAFKA_* env vars
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── jaeger/
│   │   ├── deployment.yaml
│   │   └── service.yaml         # ports: 4318 (OTLP), 16686 (UI)
│   ├── keycloak/
│   │   ├── configmap.yaml       # realm JSON
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── prometheus/
│   │   ├── configmap.yaml       # prometheus.yml
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   └── grafana/
│       ├── deployment.yaml
│       └── service.yaml
│
├── apps/                        # Application services
│   ├── schedule-service/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── inventory-service/
│   │   ├── deployment.yaml
│   │   ├── service.yaml
│   │   └── hpa.yaml             # autoscale min=1 max=6
│   ├── booking-service/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── payment-service/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── waiting-room/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── quota-service/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   ├── fare-service/
│   │   ├── deployment.yaml
│   │   └── service.yaml
│   └── api-gateway/
│       ├── deployment.yaml
│       └── service.yaml
│
└── ingress.yaml                 # / → api-gateway:8080
```

---

## 6. Mapping: Docker Compose → Kubernetes

### 6.1 `depends_on` → `readinessProbe` + `initContainer`

Compose block container B cho đến khi container A healthy.
K8s không block — thay vào đó:

1. **`readinessProbe`** trên mỗi service: nếu DB chưa ready, pod không nhận traffic,
   service khác sẽ thấy lỗi và circuit breaker handle.
2. **`initContainer`** khi cần block cứng (ví dụ postgres phải có trước khi flyway chạy):

```yaml
initContainers:
  - name: wait-for-postgres
    image: busybox:1.36
    command:
      - sh
      - -c
      - |
        until nc -z postgres 5432; do
          echo "Waiting for postgres..."
          sleep 2
        done
```

### 6.2 `ports:` → Service + Ingress

```yaml
# Compose:
ports:
  - "8082:8082"    # expose ra host

# K8s: Service chỉ ClusterIP (internal), Ingress route từ ngoài vào
# inventory-service KHÔNG cần expose ra ngoài cluster
# gateway route đến nó qua ClusterIP
```

### 6.3 `volumes:` → PVC hoặc ConfigMap

```yaml
# Compose:
volumes:
  - ./infra/postgres/init:/docker-entrypoint-initdb.d:ro
  - pgdata:/var/lib/postgresql/data

# K8s — init scripts qua ConfigMap:
volumes:
  - name: init-scripts
    configMap:
      name: postgres-init       # chứa nội dung 01-databases.sql
  - name: pgdata
    persistentVolumeClaim:
      claimName: postgres-pvc

# K8s — mount vào container:
volumeMounts:
  - name: init-scripts
    mountPath: /docker-entrypoint-initdb.d
  - name: pgdata
    mountPath: /var/lib/postgresql/data
```

### 6.4 `environment:` → ConfigMap ref + Secret ref

```yaml
# Compose:
environment:
  DB_HOST: postgres
  DB_PASSWORD: booking_pw
  KAFKA_BOOTSTRAP_SERVERS: kafka:9092

# K8s — mix ConfigMap và Secret:
envFrom:
  - configMapRef:
      name: app-config            # DB_HOST, KAFKA_*, OTEL_*, service URLs
env:
  - name: DB_PASSWORD
    valueFrom:
      secretKeyRef:
        name: postgres-secret
        key: booking-password
```

### 6.5 `healthcheck:` → readinessProbe + livenessProbe

```yaml
# Compose:
healthcheck:
  test: ["CMD-SHELL", "wget -qO- http://localhost:8083/actuator/health | grep -q UP"]
  interval: 10s
  timeout: 5s
  retries: 12
  start_period: 60s

# K8s:
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8083
  initialDelaySeconds: 20   # = start_period tương đối
  periodSeconds: 10         # = interval
  timeoutSeconds: 5         # = timeout
  failureThreshold: 3       # fail 3 lần mới tính là "not ready"

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8083
  initialDelaySeconds: 60   # liveness kiểm tra muộn hơn readiness
  periodSeconds: 15
  timeoutSeconds: 5
  failureThreshold: 3
```

### 6.6 Kafka — vấn đề `ADVERTISED_LISTENERS`

Trong Compose, Kafka advertise địa chỉ dựa trên container name (`kafka`).
Trong K8s, Service name là `kafka` → DNS là `kafka.rail-ticketing.svc.cluster.local`.
Short form trong cùng namespace: `kafka`.

```yaml
# ConfigMap cho Kafka:
KAFKA_ADVERTISED_LISTENERS: "PLAINTEXT://kafka:9092"
# "kafka" ở đây là tên Service → K8s DNS resolve tự động
# Các app service chỉ cần KAFKA_BOOTSTRAP_SERVERS=kafka:9092
```

Không cần `EXTERNAL` listener trong K8s (không có IDE chạy ngoài cluster cần
connect vào Kafka). Bỏ hoàn toàn listener EXTERNAL và port 29092.

---

## 7. Thứ tự deploy

Deploy theo thứ tự phụ thuộc. Mỗi bước có checkpoint verify.

### Bước 1 — Namespace

```powershell
kubectl apply -f k8s/namespace.yaml
kubectl get namespaces | grep rail-ticketing
```

### Bước 2 — Secrets và ConfigMaps (stateless, không fail)

```powershell
kubectl apply -f k8s/infra/postgres/secret.yaml
kubectl apply -f k8s/infra/postgres/configmap.yaml
kubectl apply -f k8s/infra/kafka/configmap.yaml
kubectl apply -f k8s/infra/keycloak/configmap.yaml
kubectl apply -f k8s/infra/prometheus/configmap.yaml
```

### Bước 3 — Storage

```powershell
kubectl apply -f k8s/infra/postgres/pvc.yaml
kubectl -n rail-ticketing get pvc   # expect: Bound
```

### Bước 4 — Infrastructure services

```powershell
kubectl apply -f k8s/infra/postgres/
kubectl apply -f k8s/infra/redis/
kubectl apply -f k8s/infra/kafka/
kubectl apply -f k8s/infra/jaeger/
kubectl apply -f k8s/infra/keycloak/

# Đợi postgres và redis ready (app services cần chúng)
kubectl -n rail-ticketing rollout status deployment/postgres
kubectl -n rail-ticketing rollout status deployment/redis
kubectl -n rail-ticketing rollout status deployment/kafka
```

### Bước 5 — App services (đúng thứ tự phụ thuộc)

```powershell
# schedule-service trước (nhiều service khác phụ thuộc)
kubectl apply -f k8s/apps/schedule-service/
kubectl -n rail-ticketing rollout status deployment/schedule-service

# inventory-service cần schedule + redis
kubectl apply -f k8s/apps/inventory-service/

# quota-service + fare-service độc lập
kubectl apply -f k8s/apps/quota-service/
kubectl apply -f k8s/apps/fare-service/

# booking-service cần inventory + quota + fare + kafka
kubectl apply -f k8s/apps/booking-service/
kubectl apply -f k8s/apps/payment-service/
kubectl apply -f k8s/apps/waiting-room/

# gateway sau cùng
kubectl apply -f k8s/apps/api-gateway/
```

### Bước 6 — HPA + Ingress

```powershell
kubectl apply -f k8s/apps/inventory-service/hpa.yaml
kubectl apply -f k8s/ingress.yaml
kubectl -n rail-ticketing get hpa
```

### Bước 7 — Observability (optional, không critical)

```powershell
kubectl apply -f k8s/infra/prometheus/
kubectl apply -f k8s/infra/grafana/
```

---

## 8. Verify end-to-end

```powershell
# 1. Tất cả pods Running
kubectl -n rail-ticketing get pods

# 2. Services có endpoints
kubectl -n rail-ticketing get endpoints

# 3. API qua Ingress (host:8080 → Ingress → api-gateway)
curl http://localhost:8080/api/v1/trips/1

# 4. Actuator health gateway
curl http://localhost:8080/actuator/health

# 5. Keycloak realm còn sống
curl http://localhost:8180/realms/rail-ticketing/.well-known/openid-configuration

# 6. HPA
kubectl -n rail-ticketing describe hpa inventory-hpa

# 7. Logs
kubectl -n rail-ticketing logs -l app=booking-service --tail=30

# 8. Scale test thủ công (xem HPA sau đó tự scale lại)
kubectl -n rail-ticketing scale deployment inventory-service --replicas=3
kubectl -n rail-ticketing rollout status deployment/inventory-service
kubectl -n rail-ticketing scale deployment inventory-service --replicas=1
```

---

## 9. Các lệnh kubectl thường dùng

```powershell
# Xem tất cả resources trong namespace
kubectl -n rail-ticketing get all

# Xem chi tiết pod (events, lỗi startup)
kubectl -n rail-ticketing describe pod <pod-name>

# Logs real-time
kubectl -n rail-ticketing logs -f deployment/booking-service

# Exec vào pod (debug)
kubectl -n rail-ticketing exec -it deployment/booking-service -- sh

# Port-forward (debug trực tiếp một service)
kubectl -n rail-ticketing port-forward svc/inventory-service 8082:8082

# Apply tất cả với kustomize
kubectl apply -k k8s/

# Delete cluster khi không dùng
k3d cluster delete rt-cluster

# Restart một deployment (sau khi re-import image)
kubectl -n rail-ticketing rollout restart deployment/booking-service
```

---

## 10. Điểm khác biệt quan trọng so với Docker Compose

### Không có restart policy

Trong Compose: `no restart policy` (Stage 4 cố ý để test failure injection).
Trong K8s: `restartPolicy: Always` mặc định cho Deployment — pods tự restart.

Để test failure injection ở Stage 11, dùng chaos tools (Chaos Monkey, LitmusChaos)
thay vì `docker kill`.

### Image tagging

Trong Compose, `latest` tag luôn là build mới nhất.
Trong K8s với `imagePullPolicy: Never`, phải tag version cụ thể khi deploy:

```powershell
docker tag rail-ticketing-booking-service:latest rail-ticketing-booking-service:v10.1
k3d image import rail-ticketing-booking-service:v10.1 -c rt-cluster
# Cập nhật image trong deployment.yaml
```

Hoặc dùng `imagePullPolicy: IfNotPresent` với unique tag mỗi lần build.

### Namespace isolation

Tất cả resources trong namespace `rail-ticketing`. DNS cross-namespace:
```
# Từ pod trong namespace khác, phải gọi full DNS:
http://api-gateway.rail-ticketing.svc.cluster.local:8080
# Trong cùng namespace, short form:
http://api-gateway:8080
```

---

**Đọc tiếp:** [COMMANDS.md](COMMANDS.md) — kubectl commands nhanh, port reference
| [02 — Architecture](02-architecture.md) — service topology gốc
| [05 — Build Progression](05-build-progression.md) — Stage 11 (observability + chaos)
