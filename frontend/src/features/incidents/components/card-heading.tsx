import * as React from 'react';
import { cn } from '@/lib/utils';

/**
 * A real `<h2>` styled like CardTitle. The shared CardTitle renders a plain div, which leaves a page of cards with no
 * heading structure for screen-reader users to navigate by.
 */
export function CardHeading({ className, ...props }: React.HTMLAttributes<HTMLHeadingElement>) {
  return <h2 className={cn('text-base font-semibold leading-none tracking-tight', className)} {...props} />;
}
