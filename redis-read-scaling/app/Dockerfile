FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod .
COPY main.go .
RUN go mod tidy && CGO_ENABLED=0 go build -o /server .

FROM alpine:3.20
RUN apk add --no-cache curl
COPY --from=build /server /server
EXPOSE 8080
ENTRYPOINT ["/server"]
