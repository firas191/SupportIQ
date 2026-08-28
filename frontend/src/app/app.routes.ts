import { Routes } from '@angular/router';
import { authGuard } from './core/guards/auth.guard';
import { roleGuard } from './core/guards/role.guard';

/**
 * Table de routage.
 *
 * Chaque ecran est charge a la demande (`loadComponent`) : le bundle initial
 * ne contient que la coquille et l'ecran demande. Concretement, un agent qui
 * ne va jamais dans les imports ne telecharge jamais le code des imports.
 *
 * Les titres alimentent l'onglet du navigateur. Ce n'est pas cosmetique : un
 * utilisateur qui garde trois onglets ouverts les distingue par leur titre, et
 * un titre parlant est repris tel quel dans les favoris et l'historique.
 */
export const routes: Routes = [
  {
    path: 'login',
    title: 'Connexion · SupportIQ',
    loadComponent: () =>
      import('./features/auth/login/login.component').then((m) => m.LoginComponent),
  },
  {
    path: '',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./layout/main-layout/main-layout.component').then((m) => m.MainLayoutComponent),
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'tickets' },
      {
        // Vue d'ensemble reservee aux responsables (aligne sur le backend, §7).
        path: 'dashboard',
        title: "Vue d'ensemble · SupportIQ",
        canActivate: [roleGuard('MANAGER')],
        loadComponent: () =>
          import('./features/dashboard/dashboard.component').then((m) => m.DashboardComponent),
      },
      {
        // Chat Insight (S6-J3) : les vues interrogees agregent l'activite de
        // toute l'equipe, meme perimetre que la vue d'ensemble.
        path: 'insight',
        title: 'Analyse · SupportIQ',
        canActivate: [roleGuard('MANAGER')],
        loadComponent: () =>
          import('./features/insight/insight.component').then((m) => m.InsightComponent),
      },
      {
        // Synthese hebdomadaire (S6-J4) : agregats de toute l'equipe, meme
        // perimetre que la vue d'ensemble et l'analyse.
        path: 'digest',
        title: 'Synthèse hebdomadaire · SupportIQ',
        canActivate: [roleGuard('MANAGER')],
        loadComponent: () =>
          import('./features/digest/digest.component').then((m) => m.DigestComponent),
      },
      {
        // Sujets emergents (S7-J1) : lecture transversale du corpus recent, meme
        // perimetre MANAGER+ que la vue d'ensemble, l'analyse et la synthese.
        path: 'topics',
        title: 'Sujets émergents · SupportIQ',
        canActivate: [roleGuard('MANAGER')],
        loadComponent: () =>
          import('./features/topics/topics.component').then((m) => m.TopicsComponent),
      },
      {
        // Ingestion documentaire (S7-J4) : ouverte aux AGENT+, contrairement a
        // l'import de fichier structure reserve aux ADMIN. Un agent depose ici
        // le PDF qu'un client vient d'envoyer — c'est du traitement, pas de
        // l'administration.
        path: 'intake',
        title: 'Documents · SupportIQ',
        loadComponent: () =>
          import('./features/intake/intake.component').then((m) => m.IntakeComponent),
      },
      {
        path: 'tickets',
        title: 'Tickets · SupportIQ',
        loadComponent: () =>
          import('./features/tickets/tickets.component').then((m) => m.TicketsComponent),
      },
      {
        path: 'tickets/:id',
        title: 'Ticket · SupportIQ',
        loadComponent: () =>
          import('./features/tickets/ticket-detail.component').then((m) => m.TicketDetailComponent),
      },
      {
        path: 'imports',
        title: 'Imports · SupportIQ',
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () =>
          import('./features/imports/import.component').then((m) => m.ImportComponent),
      },
      {
        // Base de connaissances (S5-J1) : son contenu determine ce que la
        // plateforme proposera comme reponses, d'ou la reserve aux ADMIN.
        path: 'knowledge',
        title: 'Base de connaissances · SupportIQ',
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () =>
          import('./features/knowledge/knowledge.component').then((m) => m.KnowledgeComponent),
      },
      {
        path: 'admin/users',
        title: 'Équipe · SupportIQ',
        canActivate: [roleGuard('ADMIN')],
        loadComponent: () =>
          import('./features/auth/register/register.component').then((m) => m.RegisterComponent),
      },
      {
        // Adresse inconnue : on l'affiche dans la coquille plutot que de
        // rediriger en silence. Rediriger sans rien dire fait croire a un clic
        // rate ; une page dediee explique et propose une sortie.
        path: '**',
        title: 'Page introuvable · SupportIQ',
        loadComponent: () =>
          import('./features/not-found/not-found.component').then((m) => m.NotFoundComponent),
      },
    ],
  },
];
