import { MatPaginatorIntl } from '@angular/material/paginator';

/** Textos do paginador em português (ADR-0004, D10). */
export function paginatorIntl(): MatPaginatorIntl {
  const intl = new MatPaginatorIntl();
  intl.itemsPerPageLabel = 'Itens por página';
  intl.firstPageLabel = 'Primeira página';
  intl.lastPageLabel = 'Última página';
  intl.nextPageLabel = 'Próxima página';
  intl.previousPageLabel = 'Página anterior';
  intl.getRangeLabel = (page, pageSize, length) => {
    if (length === 0) {
      return 'Nenhum item';
    }
    const start = page * pageSize + 1;
    const end = Math.min(length, (page + 1) * pageSize);
    return `${start} a ${end} de ${length}`;
  };
  return intl;
}
