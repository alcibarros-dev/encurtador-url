import { Clipboard } from '@angular/cdk/clipboard';
import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, DestroyRef, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog } from '@angular/material/dialog';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { LinksApi } from '../../core/api/links-api';
import { Link, Page } from '../../core/api/models';
import { ApiError, errorMessage } from '../../core/errors/api-error';
import { Notifier } from '../../core/errors/notifier';
import { ConfirmDialog, ConfirmDialogData } from '../../shared/dialog/confirm-dialog';
import { LinkStatusPipe } from '../../shared/format';
import { CreateLinkDialog } from './create-link.dialog';

/** Links do usuário: lista paginada, criação, cópia, destino e remoção (ADR-0004, D6). */
@Component({
  selector: 'app-links-page',
  imports: [DatePipe, LinkStatusPipe, MatButtonModule, MatPaginatorModule, MatProgressBarModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './links.page.html',
  styleUrl: './links.page.scss',
})
export class LinksPage {
  private readonly api = inject(LinksApi);
  private readonly dialog = inject(MatDialog);
  private readonly clipboard = inject(Clipboard);
  private readonly notifier = inject(Notifier);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly pageSize = LinksApi.PAGE_SIZE;
  protected readonly page = signal(0);
  protected readonly links = signal<Page<Link> | null>(null);
  protected readonly error = signal<ApiError | null>(null);
  protected readonly loading = signal(false);
  protected readonly message = errorMessage;

  constructor() {
    this.load(0);
  }

  protected load(page: number): void {
    this.page.set(page);
    this.loading.set(true);
    this.error.set(null);
    this.api
      .list(page)
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({
        next: (result) => {
          // Uma remoção pode esvaziar a última página.
          if (!result.content.length && page > 0) {
            this.load(page - 1);
            return;
          }
          this.links.set(result);
          this.loading.set(false);
        },
        error: (failure: unknown) => {
          this.error.set(ApiError.from(failure));
          this.loading.set(false);
        },
      });
  }

  protected changePage(event: PageEvent): void {
    this.load(event.pageIndex);
  }

  protected create(): void {
    this.dialog
      .open<CreateLinkDialog, void, Link>(CreateLinkDialog, { autoFocus: 'first-tabbable' })
      .afterClosed()
      .subscribe((link) => {
        if (link) {
          this.notifier.info(`Link criado: ${link.shortUrl}`);
          this.load(0);
        }
      });
  }

  protected copy(link: Link): void {
    if (this.clipboard.copy(link.shortUrl)) {
      this.notifier.info('URL curta copiada.');
    } else {
      this.notifier.error('Não foi possível copiar. Selecione a URL e copie manualmente.');
    }
  }

  protected remove(link: Link): void {
    this.dialog
      .open<ConfirmDialog, ConfirmDialogData, boolean>(ConfirmDialog, {
        data: {
          title: `Remover ${link.code}?`,
          message:
            'O código não volta a valer: quem acessar a URL curta verá que o link foi removido, e as estatísticas ' +
            'dele somem.',
          confirm: 'Remover',
        },
      })
      .afterClosed()
      .subscribe((confirmed) => {
        if (!confirmed) {
          return;
        }
        this.api.delete(link.code).subscribe({
          next: () => {
            this.notifier.info(`Link ${link.code} removido.`);
            this.load(this.page());
          },
          error: (failure: unknown) => this.notifier.error(errorMessage(ApiError.from(failure))),
        });
      });
  }
}
