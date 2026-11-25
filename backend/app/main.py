"""FastAPI application entrypoint for AthletiQ backend."""

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from app.api.routes import analytics, audit, auth, calibrations, examinations, health, reports, standards, students, users, video
from app.core.config import get_settings


def create_app() -> FastAPI:
    """Create and configure the FastAPI application instance."""
    settings = get_settings()
    app = FastAPI(
        title="AthletiQ Core API",
        version="0.1.0",
        description="Institution-isolated sports performance assessment API.",
    )

    if settings.cors_origin_list:
        app.add_middleware(
            CORSMiddleware,
            allow_origins=settings.cors_origin_list,
            allow_credentials=True,
            allow_methods=["GET", "POST", "PATCH", "PUT", "DELETE"],
            allow_headers=["Authorization", "Content-Type", "X-CSRF-Token"],
        )

    app.include_router(health.router)
    app.include_router(auth.router)
    app.include_router(users.router)
    app.include_router(students.router)
    app.include_router(calibrations.router)
    app.include_router(standards.router)
    app.include_router(examinations.router)
    app.include_router(analytics.router)
    app.include_router(reports.router)
    app.include_router(audit.router)
    app.include_router(video.router)
    return app


app = create_app()
