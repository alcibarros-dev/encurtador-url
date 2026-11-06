import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FieldTree, FormField, form, required, submit, validate } from '@angular/forms/signals';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { firstValueFrom } from 'rxjs';
import { LinksApi } from '../../core/api/links-api';
import { Link } from '../../core/api/models';
import { ApiError, errorMessage } from '../../core/errors/api-error';

interface CreateLinkModel {
  url: string;
  alias: string;
  /** Valor do `datetime-local`, no fuso do navegador; vazio para a validade padrão. */
  expiresAt: string;
}

/** Mesma regra do app (ADR-0001, D3): de 4 a 32 letras, números, `_` e `-`. */
const ALIAS = /^[A-Za-z0-9_-]{4,32}$/;

/**
 * Criação de link (ADR-0004, D6) com Signal Forms. As regras simples são conferidas aqui; as demais (destino
 * proibido, alias em uso, cota) vêm do app, e cada erro de `errors[]` vai para o campo dele.
 */
@Component({
  selector: 'app-create-link-dialog',
  imports: [FormField, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>Novo link</h2>
    <form (submit)="save($event)" novalidate>
      <mat-dialog-content>
        @if (formError(); as message) {
          <p class="erro" role="alert">{{ message }}</p>
        }
        <mat-form-field appearance="outline">
          <mat-label>Endereço de destino</mat-label>
          <input matInput type="url" inputmode="url" autocomplete="url" [formField]="form.url" cdkFocusInitial />
          <mat-hint>Começa com http:// ou https://</mat-hint>
          @for (error of form.url().errors(); track error.kind) {
            <mat-error>{{ error.message }}</mat-error>
          }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Alias (opcional)</mat-label>
          <input matInput autocomplete="off" [formField]="form.alias" />
          <mat-hint>De 4 a 32 caracteres: letras, números, _ e -</mat-hint>
          @for (error of form.alias().errors(); track error.kind) {
            <mat-error>{{ error.message }}</mat-error>
          }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Validade (opcional)</mat-label>
          <input matInput type="datetime-local" [formField]="form.expiresAt" />
          <mat-hint>Sem data, o link vale 30 dias; no máximo, 365</mat-hint>
          @for (error of form.expiresAt().errors(); track error.kind) {
            <mat-error>{{ error.message }}</mat-error>
          }
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button type="button" mat-dialog-close>Cancelar</button>
        <button mat-flat-button type="submit" [disabled]="form().submitting()">Criar link</button>
      </mat-dialog-actions>
    </form>
  `,
  styles: `
    mat-form-field {
      display: block;
      width: 100%;
      margin-bottom: 8px;
    }
    mat-dialog-content {
      min-width: min(480px, 80vw);
    }
  `,
})
export class CreateLinkDialog {
  private readonly api = inject(LinksApi);
  private readonly dialogRef = inject<MatDialogRef<CreateLinkDialog, Link>>(MatDialogRef);

  protected readonly model = signal<CreateLinkModel>({ url: '', alias: '', expiresAt: '' });
  protected readonly formError = signal<string | null>(null);
  protected readonly form = form(this.model, (path) => {
    required(path.url, { message: 'Informe o endereço de destino.' });
    validate(path.url, ({ value }) =>
      value() && !/^https?:\/\//i.test(value().trim())
        ? { kind: 'scheme', message: 'Use um endereço que comece com http:// ou https://.' }
        : undefined,
    );
    validate(path.alias, ({ value }) =>
      value() && !ALIAS.test(value())
        ? { kind: 'alias', message: 'Use de 4 a 32 caracteres: letras, números, _ e -.' }
        : undefined,
    );
    validate(path.expiresAt, ({ value }) =>
      value() && !(new Date(value()).getTime() > Date.now())
        ? { kind: 'future', message: 'Escolha uma data no futuro.' }
        : undefined,
    );
  });

  protected async save(event: Event): Promise<void> {
    event.preventDefault();
    this.formError.set(null);
    await submit(this.form, async (fields) => {
      const value = fields().value();
      try {
        const link = await firstValueFrom(
          this.api.create({
            url: value.url.trim(),
            alias: value.alias || undefined,
            expiresAt: value.expiresAt ? new Date(value.expiresAt).toISOString() : undefined,
          }),
        );
        this.dialogRef.close(link);
        return undefined;
      } catch (failure) {
        return this.serverErrors(ApiError.from(failure), fields);
      }
    });
  }

  private serverErrors(error: ApiError, fields: FieldTree<CreateLinkModel>) {
    const byField: Record<string, FieldTree<string>> = {
      url: fields.url,
      alias: fields.alias,
      expiresAt: fields.expiresAt,
    };
    if (error.code === 'ALIAS_TAKEN') {
      return { kind: 'server', message: errorMessage(error), fieldTree: fields.alias };
    }
    const targeted = error.errors.filter((item) => byField[item.field]);
    if (error.code === 'VALIDATION_FAILED' && targeted.length === error.errors.length && targeted.length) {
      return targeted.map((item) => ({ kind: 'server', message: item.message, fieldTree: byField[item.field] }));
    }
    this.formError.set(errorMessage(error));
    return undefined;
  }
}
