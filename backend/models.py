from sqlmodel import SQLModel, Field, Relationship
from typing import Optional, List
from enum import Enum
from pydantic import BaseModel


class ActionType(str, Enum):
    BLOCK = "block"
    REDIRECT = "redirect"
    CLOSE = "close"


class Group(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    name: str
    color: str = "#5C6BC0"
    is_active: bool = True
    urls: List["BlockedUrl"] = Relationship(back_populates="group")


class GroupCreate(SQLModel):
    name: str
    color: str = "#5C6BC0"


class GroupUpdate(SQLModel):
    name: Optional[str] = None
    color: Optional[str] = None
    is_active: Optional[bool] = None


class BlockedUrl(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    url: str
    description: Optional[str] = None
    is_active: bool = True
    action: ActionType = ActionType.BLOCK
    redirect_url: Optional[str] = None
    group_id: Optional[int] = Field(default=None, foreign_key="group.id")
    group: Optional[Group] = Relationship(back_populates="urls")


class BlockedUrlCreate(SQLModel):
    url: str
    description: Optional[str] = None
    action: ActionType = ActionType.BLOCK
    redirect_url: Optional[str] = None
    group_id: Optional[int] = None


class BlockedUrlUpdate(SQLModel):
    url: Optional[str] = None
    description: Optional[str] = None
    action: Optional[ActionType] = None
    redirect_url: Optional[str] = None
    group_id: Optional[int] = None
    is_active: Optional[bool] = None


class Settings(SQLModel, table=True):
    id: Optional[int] = Field(default=None, primary_key=True)
    pin_hash: Optional[str] = None


class PinVerifyRequest(BaseModel):
    pin: str


class PinSetRequest(BaseModel):
    current_pin: Optional[str] = None
    new_pin: str
