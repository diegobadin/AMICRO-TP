# Multi-stage build: compile a static binary, ship it on distroless (non-root).
FROM golang:1.22-alpine AS build
WORKDIR /src
# Modules on their own layer, so editing a source file does not re-download the tree.
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /app/server .

FROM gcr.io/distroless/static-debian12
COPY --from=build /app/server /app/server
ENV PORT=8088
EXPOSE 8088
USER nonroot:nonroot
ENTRYPOINT ["/app/server"]
