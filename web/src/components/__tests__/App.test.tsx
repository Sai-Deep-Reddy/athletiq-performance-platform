import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { App } from '../../App';

describe('AthletiQ Clinical Frontend App', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.classList.remove('dark');
  });

  it('renders AuthGate, supports zero-login Guest entry, and toggles dark theme', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ status: 'ok', database: 'connected' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ detail: 'Authentication required' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ detail: 'Authentication required' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      );

    vi.stubGlobal('fetch', fetchMock);
    render(<App />);

    await waitFor(() => expect(screen.getByText('Institutional Sign In')).toBeInTheDocument());
    expect(screen.getByPlaceholderText('examiner@institution.org')).toHaveValue('');

    // Toggle dark theme on AuthGate
    const themeBtn = screen.getByRole('button', { name: /switch to dark theme/i });
    fireEvent.click(themeBtn);
    expect(document.documentElement.classList.contains('dark')).toBe(true);
    expect(window.localStorage.getItem('athletiq_theme_mode')).toBe('dark');

    // Direct Guest Login without entering credentials
    const guestBtn = screen.getByRole('button', { name: /enter as guest \(no login\)/i });
    fireEvent.click(guestBtn);

    await waitFor(() =>
      expect(screen.getByText('Guest Field Workspace Active')).toBeInTheDocument(),
    );
    expect(screen.getByText('Kinematic Capture Launchpad')).toBeInTheDocument();

    vi.unstubAllGlobals();
  });
});
