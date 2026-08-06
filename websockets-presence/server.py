import asyncio
import json

from websockets.asyncio.server import serve

# user_id -> set of open connections (a user can have several tabs/devices)
online = {}


async def broadcast():
    """Tell every connected client who is online right now."""
    msg = json.dumps({"type": "presence", "online": sorted(online)})
    for conns in list(online.values()):
        for ws in list(conns):
            try:
                await ws.send(msg)
            except Exception:
                pass  # connection died mid-send; its handler will clean up


async def handler(ws):
    user = None
    try:
        # the client's first message is its user id
        user = await ws.recv()
        online.setdefault(user, set()).add(ws)
        print(f"+ {user} connected ({len(online[user])} tab(s))")
        await broadcast()

        # nothing else to do — just hold the line open
        async for _ in ws:
            pass
    finally:
        if user and ws in online.get(user, ()):
            online[user].discard(ws)
            if online[user]:
                print(f"  {user} closed a tab ({len(online[user])} left)")
            else:
                del online[user]
                print(f"- {user} went offline")
            await broadcast()


async def main():
    async with serve(handler, "localhost", 8765):
        print("server on ws://localhost:8765")
        await asyncio.Future()  # run forever


asyncio.run(main())
