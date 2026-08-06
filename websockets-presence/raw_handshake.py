"""Talk to the WebSocket server using a plain TCP socket, by hand.
No websockets library — so we see every byte."""
import base64
import hashlib
import os
import socket

# 1. the client invents a random 16-byte key, base64'd
key = base64.b64encode(os.urandom(16)).decode()

request = (
    "GET / HTTP/1.1\r\n"
    "Host: localhost:8765\r\n"
    "Upgrade: websocket\r\n"
    "Connection: Upgrade\r\n"
    f"Sec-WebSocket-Key: {key}\r\n"
    "Sec-WebSocket-Version: 13\r\n"
    "\r\n"
)

s = socket.create_connection(("localhost", 8765))
s.sendall(request.encode())

print("=== WE SENT (plain HTTP!) ===")
print(request)

response = s.recv(4096).decode()
print("=== SERVER REPLIED ===")
print(response)

# 2. verify the server's proof: base64(sha1(key + magic GUID))
GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
expected = base64.b64encode(hashlib.sha1((key + GUID).encode()).digest()).decode()

print("=== THE PROOF ===")
print(f"our key         : {key}")
print(f"magic GUID      : {GUID}")
print(f"sha1 -> base64  : {expected}")
print(f"server said     : {[l for l in response.splitlines() if 'Accept' in l]}")
print(f"match           : {expected in response}")

s.close()
