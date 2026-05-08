import asyncio
import websockets
import json

async def test():
    async with websockets.connect("ws://localhost:8080/ws/telemetry") as ws:
        msg = await ws.recv()
        print(json.loads(msg))

asyncio.run(test())
