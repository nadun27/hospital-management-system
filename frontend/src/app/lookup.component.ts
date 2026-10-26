import {
  Component,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  inject,
} from "@angular/core";
import { FormsModule } from "@angular/forms";
import { Api, Row } from "./api.service";

@Component({
  selector: "app-lookup",
  standalone: true,
  imports: [FormsModule],
  template: `
    <div class="lookup-search">
      <input
        [ngModel]="search"
        (ngModelChange)="filter($event)"
        [ngModelOptions]="{ standalone: true }"
        placeholder="Type to find {{ kind }}…"
        aria-label="Search options"
      />
    </div>
    <select
      [ngModel]="value"
      (ngModelChange)="valueChange.emit($event)"
      [ngModelOptions]="{ standalone: true }"
      [required]="required"
      [attr.aria-label]="kind"
    >
      <option [ngValue]="null">Select…</option>
      @if (value && !containsValue()) {
        <option [ngValue]="value">Selected #{{ value }}</option>
      }
      @for (o of options; track o["id"]) {
        <option [ngValue]="o['id']">{{ o["label"] }}</option>
      }
    </select>
    @if (error) {
      <small class="error">{{ error }}</small>
    }
  `,
})
export class LookupComponent implements OnChanges {
  @Input() kind = "";
  @Input() value: any = null;
  @Input() required = false;
  @Output() valueChange = new EventEmitter<any>();
  private api = inject(Api);
  options: Row[] = [];
  search = "";
  error = "";
  private timer: any;
  private sequence = 0;
  ngOnChanges() {
    void this.load();
  }
  containsValue() {
    return this.options.some((o) => String(o["id"]) === String(this.value));
  }
  filter(value: string) {
    this.search = value;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => void this.load(), 250);
  }
  async load() {
    const seq = ++this.sequence;
    try {
      const options = await this.api.get<Row[]>(
        "/lookups/" + this.kind + "?q=" + encodeURIComponent(this.search),
      );
      if (seq === this.sequence) {
        this.options = options;
        this.error = "";
      }
    } catch (e) {
      if (seq === this.sequence) this.error = this.api.message(e);
    }
  }
}
