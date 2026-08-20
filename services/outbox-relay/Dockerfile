# Multi-stage build: compile a static binary, ship it on distroless (non-root).
FROM golang:1.22-alpine AS build
WORKDIR /src
# Modules on their own layer, so editing a source file does not re-download the tree.
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /app/server .

# Mirrored base (ci/mirror-bases.yml), pinned by digest: gcr.io rejected these runners six times
# across P6-P8 and kaniko does not retry a base pull, so every retry was a human clicking the job.
FROM registry.gitlab.com/itba-73-40-microservicios/alumnos/2026-s1/grupo-4/amicro-tp/base/static-debian12@sha256:6447365a6337c3732f412d1b74357b30a633831955b2bc45552b0086be907687
COPY --from=build /app/server /app/server
ENV PORT=8088
EXPOSE 8088
USER nonroot:nonroot
ENTRYPOINT ["/app/server"]
