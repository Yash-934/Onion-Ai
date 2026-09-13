import os, json
import httpx
from fastapi import FastAPI, Header, HTTPException
from fastapi.responses import StreamingResponse, JSONResponse

UPSTREAM = os.environ.get("UPSTREAM_BASE_URL", "").rstrip("/")
UPSTREAM_KEY = os.environ.get("UPSTREAM_API_KEY", "")
CHAT_MODEL = os.environ.get("CHAT_MODEL", "your-chat-model")
IMAGE_MODEL = os.environ.get("IMAGE_MODEL", "your-image-model")
UPSTREAM_PROTOCOL = os.environ.get("UPSTREAM_PROTOCOL", "openai_chat").lower()
app = FastAPI(title="Private AI Gateway", docs_url=None, redoc_url=None)

def auth_ok(authorization=None, x_api_key=None):
    expected = os.environ.get("GATEWAY_API_KEY", "")
    return not expected or authorization == f"Bearer {expected}" or x_api_key == expected

def require_auth(authorization, x_api_key):
    if not auth_ok(authorization, x_api_key): raise HTTPException(401, "Unauthorized")

def upstream_headers():
    return {"Authorization": f"Bearer {UPSTREAM_KEY}", "Content-Type": "application/json"}

def anthropic_to_openai(body):
    messages=[]
    system=body.get("system")
    if system:
        if isinstance(system,list): system="\n".join(x.get("text","") for x in system if isinstance(x,dict))
        messages.append({"role":"system","content":system})
    for msg in body.get("messages",[]):
        content=msg.get("content","")
        if isinstance(content,list):
            content="\n".join(x.get("text","") for x in content if isinstance(x,dict) and x.get("type")=="text")
        messages.append({"role":msg.get("role","user"),"content":content})
    out={"model":body.get("model",CHAT_MODEL),"messages":messages,"stream":True}
    for k in ("max_tokens","temperature","top_p"):
        if k in body: out[k]=body[k]
    if "stop_sequences" in body: out["stop"]=body["stop_sequences"]
    return out

def sse(name,payload):
    return f"event: {name}\ndata: {json.dumps(payload,separators=(',',':'))}\n\n".encode()

def anthropic_start(model):
    return [
      sse("message_start",{"type":"message_start","message":{"id":"msg_private_ai","type":"message","role":"assistant","model":model,"content":[],"stop_reason":None,"stop_sequence":None,"usage":{"input_tokens":0,"output_tokens":0}}}),
      sse("content_block_start",{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}})
    ]

@app.get("/models")
@app.get("/v1/models")
async def models(authorization: str|None=Header(default=None), x_api_key: str|None=Header(default=None)):
    require_auth(authorization,x_api_key)
    data=[{"id":CHAT_MODEL,"object":"model","owned_by":"private"}]
    if IMAGE_MODEL: data.append({"id":IMAGE_MODEL,"object":"model","owned_by":"private-image"})
    return {"object":"list","data":data}

@app.post("/chat/completions")
async def chat(body:dict, authorization:str|None=Header(default=None), x_api_key:str|None=Header(default=None)):
    require_auth(authorization,x_api_key)
    if not UPSTREAM: raise HTTPException(503,"Upstream not configured")
    body=dict(body); body.setdefault("stream",True)
    async def stream():
        async with httpx.AsyncClient(timeout=None) as client:
            async with client.stream("POST",f"{UPSTREAM}/chat/completions",json=body,headers=upstream_headers()) as r:
                if r.status_code>=400:
                    detail=await r.aread(); yield b"data: "+detail+b"\n\n"; return
                async for line in r.aiter_lines():
                    if line: yield (line+"\n\n").encode()
    return StreamingResponse(stream(),media_type="text/event-stream")

@app.post("/v1/messages")
async def anthropic_messages(body:dict, authorization:str|None=Header(default=None), x_api_key:str|None=Header(default=None)):
    require_auth(authorization,x_api_key)
    if not UPSTREAM: raise HTTPException(503,"Upstream not configured")
    direct=UPSTREAM_PROTOCOL=="anthropic"
    forward=dict(body); forward["stream"]=True
    if not direct: forward=anthropic_to_openai(body)
    async def stream():
        async with httpx.AsyncClient(timeout=None) as client:
            endpoint=f"{UPSTREAM}/v1/messages" if direct else f"{UPSTREAM}/chat/completions"
            async with client.stream("POST",endpoint,json=forward,headers=upstream_headers()) as r:
                if r.status_code>=400:
                    detail=(await r.aread()).decode(errors="replace")
                    yield sse("error",{"type":"error","error":{"type":"upstream_error","message":detail}}); return
                if direct:
                    async for line in r.aiter_lines():
                        if line: yield (line+"\n\n").encode()
                    return
                started=False
                async for line in r.aiter_lines():
                    if not line.startswith("data:"): continue
                    raw=line[5:].strip()
                    if raw=="[DONE]":
                        if started:
                            yield sse("content_block_stop",{"type":"content_block_stop","index":0})
                            yield sse("message_delta",{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":None},"usage":{"output_tokens":0}})
                            yield sse("message_stop",{"type":"message_stop"})
                        return
                    try:
                        obj=json.loads(raw); choice=(obj.get("choices") or [{}])[0]
                        delta=(choice.get("delta") or {}).get("content") or ""
                        if not started:
                            for e in anthropic_start(body.get("model",CHAT_MODEL)): yield e
                            started=True
                        if delta: yield sse("content_block_delta",{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":delta}})
                    except json.JSONDecodeError: continue
    return StreamingResponse(stream(),media_type="text/event-stream")

@app.post("/images/generations")
async def images(body:dict, authorization:str|None=Header(default=None), x_api_key:str|None=Header(default=None)):
    require_auth(authorization,x_api_key)
    if not UPSTREAM: raise HTTPException(503,"Upstream not configured")
    async with httpx.AsyncClient(timeout=None) as client:
        r=await client.post(f"{UPSTREAM}/images/generations",json=body,headers=upstream_headers())
    return JSONResponse(status_code=r.status_code,content=r.json())
