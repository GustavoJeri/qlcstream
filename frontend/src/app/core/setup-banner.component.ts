import { ChangeDetectionStrategy, Component, DestroyRef, inject } from '@angular/core';
import { NavigationEnd, Router, RouterLink } from '@angular/router';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { filter } from 'rxjs';
import { IntegrationAvailabilityService } from './integration-availability.service';

@Component({
  selector: 'app-setup-banner', imports: [RouterLink], changeDetection: ChangeDetectionStrategy.OnPush,
  template: `@if (availability.state() === 'error' || (availability.state() === 'ready' && (availability.rows().some(incomplete) || availability.storageAttention()))) {
    <aside class="page" role="status"><p>{{ availability.state() === 'error' ? 'Não foi possível conferir as configurações.' : 'Confira as integrações e a pasta dos downloads. Você pode configurar por etapas e adiar os downloads.' }} <a routerLink="/setup">Abrir assistente de configuração</a> <button class="btn" type="button" (click)="availability.refresh()">Conferir novamente</button></p></aside>
  }`,
})
export class SetupBannerComponent {
  readonly availability = inject(IntegrationAvailabilityService);
  readonly incomplete = (row: { configured: boolean }) => !row.configured;
  constructor() {
    this.availability.refresh();
    inject(Router).events.pipe(filter(event => event instanceof NavigationEnd), takeUntilDestroyed(inject(DestroyRef)))
      .subscribe(() => this.availability.refresh());
  }
}
