import { Pipe, PipeTransform } from '@angular/core';
import { LinkStatus } from '../core/api/models';

/** `AAAA-MM-DD` → `DD/MM/AAAA`, sem passar por `Date` (e por fuso nenhum). */
export function dayLabel(day: string): string {
  const [year, month, date] = day.split('-');
  return `${date}/${month}/${year}`;
}

@Pipe({ name: 'day' })
export class DayPipe implements PipeTransform {
  transform(day: string): string {
    return dayLabel(day);
  }
}

export function statusLabel(status: LinkStatus): string {
  return status === 'ACTIVE' ? 'Ativo' : 'Expirado';
}

@Pipe({ name: 'linkStatus' })
export class LinkStatusPipe implements PipeTransform {
  transform(status: LinkStatus): string {
    return statusLabel(status);
  }
}
