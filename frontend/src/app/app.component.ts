import { Component, inject } from "@angular/core";
import { CommonModule } from "@angular/common";
import { FormsModule } from "@angular/forms";
import {
  Router,
  RouterLink,
  RouterLinkActive,
  RouterOutlet,
} from "@angular/router";
import { Api } from "./api.service";
import { modules } from "./modules";

@Component({
  selector: "app-root",
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    RouterOutlet,
    RouterLink,
    RouterLinkActive,
  ],
  template: `
    @if (!api.ready()) {
      <main class="loading-screen">
        <span class="brand-symbol">+</span>
        <p>Opening CareDesk…</p>
      </main>
    } @else if (!api.user()) {
      <main class="login-layout">
        <section class="login-story">
          <a class="brand"><span class="brand-symbol">+</span> CareDesk</a>
          <div>
            <span class="eyebrow light">HOSPITAL MANAGEMENT SYSTEM</span>
            <h1>Better organized.<br />Better care.</h1>
            <p>
              One workspace for the people, appointments,<br />and services that
              keep your hospital moving.
            </p>
          </div>
          <small
            >Academic demonstration · Use fictional patient information</small
          >
        </section>
        <section class="login-panel">
          <form (ngSubmit)="login()" #loginForm="ngForm">
            <span class="eyebrow">WELCOME BACK</span>
            <h2>Sign in to your workspace</h2>
            <p class="muted">
              Enter the staff account created by your administrator.
            </p>
            <label
              >Username<input
                name="username"
                [(ngModel)]="username"
                autocomplete="username"
                required
                maxlength="60"
            /></label>
            <label
              >Password<input
                name="password"
                [(ngModel)]="password"
                type="password"
                autocomplete="current-password"
                required
                maxlength="200"
            /></label>
            @if (error) {
              <p class="error" role="alert">{{ error }}</p>
            }
            <button class="primary full" [disabled]="busy || loginForm.invalid">
              {{ busy ? "Signing in…" : "Sign in →" }}
            </button>
            <p class="small muted">
              Your access is based on your assigned hospital role.
            </p>
          </form>
        </section>
      </main>
    } @else {
      <div class="app-layout">
        <aside class="sidebar" [class.mobile-open]="menuOpen">
          <a routerLink="/dashboard" class="brand"
            ><span class="brand-symbol">+</span> CareDesk</a
          >
          <div class="sidebar-caption">HOSPITAL WORKSPACE</div>
          <nav>
            <a
              routerLink="/dashboard"
              routerLinkActive="active"
              (click)="menuOpen = false"
              ><span class="nav-glyph">◈</span> Overview</a
            >
            @for (group of groups; track group) {
              @if (groupModules(group).length) {
                <div class="nav-group">{{ group }}</div>
              }
              @for (m of groupModules(group); track m.key) {
                <a
                  [routerLink]="'/' + m.key"
                  routerLinkActive="active"
                  (click)="menuOpen = false"
                  ><span class="nav-dot"></span>{{ m.title }}</a
                >
              }
            }
            <div class="nav-group">Insights</div>
            <a
              routerLink="/reports"
              routerLinkActive="active"
              (click)="menuOpen = false"
              ><span class="nav-glyph">▤</span> Reports</a
            >
          </nav>
        </aside>
        <div class="main-layout">
          <header class="topbar">
            <button
              type="button"
              class="menu-toggle quiet"
              (click)="menuOpen = !menuOpen"
              aria-label="Toggle navigation"
            >
              ☰</button
            ><span class="topbar-label"
              >Hospital operations
              <span class="muted">/ Staff workspace</span></span
            >
            <div class="user-area">
              <span class="avatar">{{ api.user()?.name?.slice(0, 1) }}</span>
              <div>
                <strong>{{ api.user()?.name }}</strong
                ><small>{{ roleName(api.user()?.role || "") }}</small>
              </div>
              <button
                type="button"
                class="theme-switch"
                role="switch"
                aria-label="Dark mode"
                [attr.aria-checked]="isDark"
                [attr.title]="
                  isDark ? 'Switch to light mode' : 'Switch to dark mode'
                "
                (click)="toggleTheme()"
              >
                <span aria-hidden="true">{{ isDark ? "☾" : "☀" }}</span>
              </button>
              <button type="button" class="quiet" (click)="settings = true">
                Password</button
              ><button type="button" class="quiet" (click)="logout()">
                Sign out
              </button>
            </div>
          </header>
          <main class="workspace"><router-outlet /></main>
        </div>
      </div>
    }
    @if (settings) {
      <div class="modal-backdrop">
        <section
          class="modal small-modal"
          role="dialog"
          aria-modal="true"
          aria-labelledby="password-title"
        >
          <div class="modal-header">
            <h2 id="password-title">Change password</h2>
            <button class="quiet" (click)="settings = false" aria-label="Close">
              ×
            </button>
          </div>
          <form (ngSubmit)="changePassword()" #pf="ngForm">
            <label
              >Current password<input
                name="old"
                [(ngModel)]="oldPassword"
                type="password"
                required
                autocomplete="current-password" /></label
            ><label
              >New password<input
                name="next"
                [(ngModel)]="newPassword"
                type="password"
                minlength="10"
                maxlength="200"
                required
                autocomplete="new-password"
            /></label>
            @if (error) {
              <p class="error">{{ error }}</p>
            }
            <p class="muted small">
              You will sign in again after changing your password.
            </p>
            <button class="primary" [disabled]="busy || pf.invalid">
              Update password
            </button>
          </form>
        </section>
      </div>
    }
  `,
})
export class AppComponent {
  api = inject(Api);
  private router = inject(Router);
  username = "";
  password = "";
  busy = false;
  error = "";
  menuOpen = false;
  settings = false;
  oldPassword = "";
  newPassword = "";
  groups = ["Patient care", "Services", "Finance", "Administration"];

  isDark = false;

  constructor() {
    this.isDark = window.matchMedia("(prefers-color-scheme: dark)").matches;

    try {
      const saved = localStorage.getItem("hms-theme");

      if (saved === "dark" || saved === "light") {
        this.isDark = saved === "dark";
      }
    } catch {}

    this.applyTheme();
    void this.api.init();
  }

  toggleTheme() {
    this.isDark = !this.isDark;
    this.applyTheme();

    try {
      localStorage.setItem("hms-theme", this.isDark ? "dark" : "light");
    } catch {}
  }

  private applyTheme() {
    document.documentElement.setAttribute(
      "data-theme",
      this.isDark ? "dark" : "light",
    );
  }
  groupModules(group: string) {
    return modules.filter((m) => m.group === group && this.api.can(...m.roles));
  }
  roleName(role: string) {
    return (
      (
        {
          ADMIN: "Administrator",
          LAB: "Laboratory staff",
          PHARMACIST: "Pharmacist",
          DOCTOR: "Doctor",
          NURSE: "Nurse",
          RECEPTIONIST: "Receptionist",
          ACCOUNTANT: "Accountant",
        } as Record<string, string>
      )[role] || role
    );
  }
  async login() {
    this.busy = true;
    this.error = "";
    try {
      await this.api.login(this.username, this.password);
      this.password = "";
      await this.router.navigateByUrl("/dashboard");
    } catch (e) {
      this.error = this.api.message(e);
    } finally {
      this.busy = false;
    }
  }
  async logout() {
    try {
      await this.api.logout();
    } catch {}
    this.menuOpen = false;
    this.error = "";
  }
  async changePassword() {
    this.busy = true;
    this.error = "";
    try {
      await this.api.post("/auth/password", {
        old_password: this.oldPassword,
        new_password: this.newPassword,
      });
      this.settings = false;
      this.oldPassword = "";
      this.newPassword = "";
      this.api.user.set(null);
    } catch (e) {
      this.error = this.api.message(e);
    } finally {
      this.busy = false;
    }
  }
}
