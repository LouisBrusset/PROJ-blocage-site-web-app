import hashlib
from typing import List, Optional

from fastapi import FastAPI, HTTPException, Depends
from fastapi.middleware.cors import CORSMiddleware
from sqlmodel import Session, select

from database import create_db_and_tables, engine, get_session
from models import (
    BlockedUrl, BlockedUrlCreate, BlockedUrlUpdate,
    Group, GroupCreate, GroupUpdate,
    Settings, PinVerifyRequest, PinSetRequest,
)


app = FastAPI(title="Site Blocker API", version="1.0.0")

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)


@app.on_event("startup")
def on_startup():
    create_db_and_tables()
    # Ensure a Settings row exists
    with Session(engine) as session:
        _ensure_settings(session)


def _ensure_settings(session: Session) -> Settings:
    settings = session.exec(select(Settings)).first()
    if not settings:
        settings = Settings()
        session.add(settings)
        session.commit()
        session.refresh(settings)
    return settings


def _pin_hash(pin: str) -> str:
    return hashlib.sha256(pin.encode()).hexdigest()


# ─────────────────────────── URLs ────────────────────────────

@app.get("/api/urls", response_model=List[BlockedUrl])
def list_urls(session: Session = Depends(get_session)):
    return session.exec(select(BlockedUrl)).all()


@app.post("/api/urls", response_model=BlockedUrl, status_code=201)
def create_url(data: BlockedUrlCreate, session: Session = Depends(get_session)):
    url = BlockedUrl.model_validate(data)
    session.add(url)
    session.commit()
    session.refresh(url)
    return url


@app.get("/api/urls/{url_id}", response_model=BlockedUrl)
def get_url(url_id: int, session: Session = Depends(get_session)):
    url = session.get(BlockedUrl, url_id)
    if not url:
        raise HTTPException(404, "URL not found")
    return url


@app.put("/api/urls/{url_id}", response_model=BlockedUrl)
def update_url(url_id: int, data: BlockedUrlUpdate, session: Session = Depends(get_session)):
    url = session.get(BlockedUrl, url_id)
    if not url:
        raise HTTPException(404, "URL not found")
    for field, value in data.model_dump(exclude_unset=True).items():
        setattr(url, field, value)
    session.add(url)
    session.commit()
    session.refresh(url)
    return url


@app.delete("/api/urls/{url_id}", status_code=204)
def delete_url(url_id: int, session: Session = Depends(get_session)):
    url = session.get(BlockedUrl, url_id)
    if not url:
        raise HTTPException(404, "URL not found")
    session.delete(url)
    session.commit()


@app.patch("/api/urls/{url_id}/toggle", response_model=BlockedUrl)
def toggle_url(url_id: int, session: Session = Depends(get_session)):
    url = session.get(BlockedUrl, url_id)
    if not url:
        raise HTTPException(404, "URL not found")
    url.is_active = not url.is_active
    session.add(url)
    session.commit()
    session.refresh(url)
    return url


# ─────────────────────────── Groups ──────────────────────────

@app.get("/api/groups", response_model=List[Group])
def list_groups(session: Session = Depends(get_session)):
    return session.exec(select(Group)).all()


@app.post("/api/groups", response_model=Group, status_code=201)
def create_group(data: GroupCreate, session: Session = Depends(get_session)):
    group = Group.model_validate(data)
    session.add(group)
    session.commit()
    session.refresh(group)
    return group


@app.get("/api/groups/{group_id}", response_model=Group)
def get_group(group_id: int, session: Session = Depends(get_session)):
    group = session.get(Group, group_id)
    if not group:
        raise HTTPException(404, "Group not found")
    return group


@app.put("/api/groups/{group_id}", response_model=Group)
def update_group(group_id: int, data: GroupUpdate, session: Session = Depends(get_session)):
    group = session.get(Group, group_id)
    if not group:
        raise HTTPException(404, "Group not found")
    for field, value in data.model_dump(exclude_unset=True).items():
        setattr(group, field, value)
    session.add(group)
    session.commit()
    session.refresh(group)
    return group


@app.delete("/api/groups/{group_id}", status_code=204)
def delete_group(group_id: int, session: Session = Depends(get_session)):
    group = session.get(Group, group_id)
    if not group:
        raise HTTPException(404, "Group not found")
    # Detach URLs from the group before deleting
    urls = session.exec(select(BlockedUrl).where(BlockedUrl.group_id == group_id)).all()
    for u in urls:
        u.group_id = None
        session.add(u)
    session.delete(group)
    session.commit()


@app.patch("/api/groups/{group_id}/toggle", response_model=Group)
def toggle_group(group_id: int, session: Session = Depends(get_session)):
    group = session.get(Group, group_id)
    if not group:
        raise HTTPException(404, "Group not found")
    group.is_active = not group.is_active
    # Cascade to all URLs in this group
    urls = session.exec(select(BlockedUrl).where(BlockedUrl.group_id == group_id)).all()
    for u in urls:
        u.is_active = group.is_active
        session.add(u)
    session.add(group)
    session.commit()
    session.refresh(group)
    return group


# ─────────────────────────── Settings / PIN ──────────────────

@app.get("/api/settings/has-pin")
def has_pin(session: Session = Depends(get_session)):
    settings = _ensure_settings(session)
    return {"has_pin": settings.pin_hash is not None}


@app.post("/api/settings/verify-pin")
def verify_pin(data: PinVerifyRequest, session: Session = Depends(get_session)):
    settings = _ensure_settings(session)
    if settings.pin_hash is None:
        return {"valid": True}
    return {"valid": _pin_hash(data.pin) == settings.pin_hash}


@app.post("/api/settings/set-pin")
def set_pin(data: PinSetRequest, session: Session = Depends(get_session)):
    settings = _ensure_settings(session)
    # If a PIN already exists, require current_pin
    if settings.pin_hash is not None:
        if not data.current_pin or _pin_hash(data.current_pin) != settings.pin_hash:
            raise HTTPException(403, "Current PIN incorrect")
    settings.pin_hash = _pin_hash(data.new_pin)
    session.add(settings)
    session.commit()
    return {"ok": True}


@app.delete("/api/settings/pin")
def remove_pin(data: PinVerifyRequest, session: Session = Depends(get_session)):
    settings = _ensure_settings(session)
    if settings.pin_hash and _pin_hash(data.pin) != settings.pin_hash:
        raise HTTPException(403, "PIN incorrect")
    settings.pin_hash = None
    session.add(settings)
    session.commit()
    return {"ok": True}


# ─────────────────────────── VPN config ──────────────────────

@app.get("/api/vpn/config")
def get_vpn_config(session: Session = Depends(get_session)):
    """
    Returns the list of domains to block, used by the Android VPN service.
    A URL entry like 'youtube.com' will also block '*.youtube.com'.
    """
    active_urls = session.exec(
        select(BlockedUrl).where(BlockedUrl.is_active == True)
    ).all()

    return {"blocked": [{"id": u.id, "url": u.url} for u in active_urls]}
