FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /callbridge .

FROM gcr.io/distroless/static
COPY --from=build /callbridge /callbridge
EXPOSE 8080
ENTRYPOINT ["/callbridge"]
