import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { Role } from '../models/auth.models';

// Hierarchie RBAC alignee sur le backend : ADMIN > MANAGER > AGENT.
const RANK: Record<Role, number> = { AGENT: 1, MANAGER: 2, ADMIN: 3 };

/**
 * Exige un role >= minRole (hierarchique). Insuffisant -> /tickets ; non connecte -> /login.
 *
 * Le repli est la route /tickets (ouverte a tout role authentifie) et non /dashboard : depuis
 * S4-J2 le dashboard est reserve aux MANAGER+, donc y rediriger un AGENT bouclerait a l'infini.
 */
export function roleGuard(minRole: Role): CanActivateFn {
  return () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    const role = auth.role();
    if (role && RANK[role] >= RANK[minRole]) {
      return true;
    }
    return router.createUrlTree([auth.isAuthenticated() ? '/tickets' : '/login']);
  };
}
