import { ChangeDetectionStrategy, Component, computed, model } from '@angular/core';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { PERIOD_OPTIONS, zoneOptions } from './period';

/** Período das estatísticas: 7, 30 ou 90 dias, num fuso escolhido (ADR-0004, D6). */
@Component({
  selector: 'app-period-picker',
  imports: [MatButtonToggleModule, MatFormFieldModule, MatSelectModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <div class="seletor" role="group" aria-label="Período das estatísticas">
      <mat-button-toggle-group
        [value]="days()"
        (change)="days.set($event.value)"
        aria-label="Quantidade de dias"
        hideSingleSelectionIndicator
      >
        @for (option of options; track option) {
          <mat-button-toggle [value]="option">{{ option }} dias</mat-button-toggle>
        }
      </mat-button-toggle-group>
      <mat-form-field appearance="outline" subscriptSizing="dynamic">
        <mat-label>Fuso horário</mat-label>
        <mat-select [value]="zone()" (selectionChange)="zone.set($event.value)">
          @for (option of zones(); track option) {
            <mat-option [value]="option">{{ option }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
    </div>
  `,
  styles: `
    .seletor {
      display: flex;
      flex-wrap: wrap;
      gap: 16px;
      align-items: center;
      margin: 8px 0 16px;
    }
    mat-form-field {
      min-width: 240px;
    }
  `,
})
export class PeriodPicker {
  readonly days = model.required<number>();
  readonly zone = model.required<string>();

  protected readonly options = PERIOD_OPTIONS;
  protected readonly zones = computed(() => zoneOptions(this.zone()));
}
