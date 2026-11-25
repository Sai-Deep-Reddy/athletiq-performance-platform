"""Application configuration loaded strictly from environment variables."""

from functools import lru_cache
from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Runtime settings for the AthletiQ FastAPI backend."""

    model_config = SettingsConfigDict(
        env_prefix="ATHLETIQ_",
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )

    env: str = Field(default="development")
    database_url: str = Field(..., min_length=1)
    jwt_secret: str = Field(..., min_length=32)
    jwt_algorithm: str = Field(default="HS256")
    access_token_expire_minutes: int = Field(default=30, ge=5, le=1440)
    cors_origins: str = Field(default="")
    auth_cookie_name: str = Field(default="athletiq_access")
    csrf_cookie_name: str = Field(default="athletiq_csrf")
    cookie_secure: bool | None = Field(default=None)
    cookie_samesite: str = Field(default="lax")

    @field_validator("cookie_samesite")
    @classmethod
    def validate_cookie_samesite(cls, value: str) -> str:
        lowered = value.strip().lower()
        if lowered not in {"lax", "strict", "none"}:
            raise ValueError("cookie_samesite must be lax, strict, or none")
        return lowered

    @property
    def is_cookie_secure(self) -> bool:
        if self.cookie_secure is not None:
            return self.cookie_secure
        return self.env not in {"development", "test"}

    @field_validator("jwt_algorithm")
    @classmethod
    def validate_jwt_algorithm(cls, value: str) -> str:
        allowed = {"HS256", "HS384", "HS512"}
        if value not in allowed:
            raise ValueError(f"Unsupported JWT algorithm '{value}'. Allowed: {sorted(allowed)}")
        return value

    @property
    def cors_origin_list(self) -> list[str]:
        return [origin.strip() for origin in self.cors_origins.split(",") if origin.strip()]


@lru_cache
def get_settings() -> Settings:
    """Return cached validated settings instance."""
    return Settings()
