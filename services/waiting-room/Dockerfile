# Build context là REPOSITORY ROOT (khớp convention các Dockerfile Java khác):
#   docker build -f services/waiting-room/Dockerfile -t waiting-room .
FROM golang:1.27-alpine AS build
WORKDIR /src

# go.mod/go.sum trước source — cache layer dependency riêng, chỉ tải lại khi go.mod đổi.
COPY services/waiting-room/go.mod services/waiting-room/go.sum ./
RUN go mod download

COPY services/waiting-room/cmd cmd
COPY services/waiting-room/internal internal
# CGO_ENABLED=0: build static binary, chạy được trên distroless/alpine không cần libc runtime.
RUN CGO_ENABLED=0 go build -o /app/waiting-room ./cmd/api

FROM alpine:3.20
WORKDIR /app
COPY --from=build /app/waiting-room .
EXPOSE 8085
ENTRYPOINT ["/app/waiting-room"]
