import {
  ChangeDetectionStrategy,
  Component,
  DestroyRef,
  ElementRef,
  afterRenderEffect,
  inject,
  input,
  viewChild,
} from '@angular/core';
import { BarController, BarElement, CategoryScale, Chart, LinearScale, Tooltip } from 'chart.js';
import { DailyClicks } from '../../core/api/models';
import { dayLabel } from '../format';

Chart.register(BarController, BarElement, CategoryScale, LinearScale, Tooltip);

/**
 * Cliques por dia num gráfico de barras do Chart.js (ADR-0004, D3). O gráfico é só visual: os mesmos números ficam
 * na tabela ao lado, para leitores de tela (ADR-0004, D10).
 */
@Component({
  selector: 'app-daily-chart',
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `<div class="grafico"><canvas #canvas role="img" [attr.aria-label]="label()"></canvas></div>`,
  styles: `
    .grafico {
      position: relative;
      height: 260px;
    }
  `,
})
export class DailyChart {
  readonly daily = input.required<DailyClicks[]>();
  readonly label = input.required<string>();

  private readonly canvas = viewChild.required<ElementRef<HTMLCanvasElement>>('canvas');
  private chart?: Chart<'bar'>;

  constructor() {
    afterRenderEffect(() => this.render(this.canvas().nativeElement, this.daily()));
    inject(DestroyRef).onDestroy(() => this.chart?.destroy());
  }

  private render(canvas: HTMLCanvasElement, daily: DailyClicks[]): void {
    const labels = daily.map((day) => dayLabel(day.date).slice(0, 5));
    const data = daily.map((day) => day.clicks);
    if (this.chart) {
      this.chart.data.labels = labels;
      this.chart.data.datasets[0].data = data;
      this.chart.update();
      return;
    }
    if (!canvas.getContext('2d')) {
      return;
    }
    const color = getComputedStyle(canvas).getPropertyValue('--mat-sys-primary').trim() || '#1f5fbf';
    this.chart = new Chart(canvas, {
      type: 'bar',
      data: { labels, datasets: [{ label: 'Cliques', data, backgroundColor: color }] },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        plugins: { legend: { display: false } },
        scales: { y: { beginAtZero: true, ticks: { precision: 0 } } },
      },
    });
  }
}
