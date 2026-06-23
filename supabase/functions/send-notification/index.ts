import { createClient } from 'https://esm.sh/@supabase/supabase-js@2'

interface ServiceAccount {
  project_id: string
  client_email: string
  private_key: string
}

// Build a short-lived OAuth2 access token from a Firebase service account.
async function getAccessToken(sa: ServiceAccount): Promise<string> {
  const now = Math.floor(Date.now() / 1000)

  const b64url = (obj: object) =>
    btoa(JSON.stringify(obj))
      .replace(/=/g, '')
      .replace(/\+/g, '-')
      .replace(/\//g, '_')

  const header  = b64url({ alg: 'RS256', typ: 'JWT' })
  const payload = b64url({
    iss:   sa.client_email,
    scope: 'https://www.googleapis.com/auth/firebase.messaging',
    aud:   'https://oauth2.googleapis.com/token',
    iat:   now,
    exp:   now + 3600,
  })

  const unsigned = `${header}.${payload}`

  const pemBody = sa.private_key
    .replace(/-----BEGIN PRIVATE KEY-----/g, '')
    .replace(/-----END PRIVATE KEY-----/g, '')
    .replace(/\n/g, '')

  const binaryDer = Uint8Array.from(atob(pemBody), c => c.charCodeAt(0))

  const key = await crypto.subtle.importKey(
    'pkcs8',
    binaryDer,
    { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' },
    false,
    ['sign'],
  )

  const sigBuffer = await crypto.subtle.sign(
    'RSASSA-PKCS1-v1_5',
    key,
    new TextEncoder().encode(unsigned),
  )

  const sig = btoa(String.fromCharCode(...new Uint8Array(sigBuffer)))
    .replace(/=/g, '')
    .replace(/\+/g, '-')
    .replace(/\//g, '_')

  const jwt = `${unsigned}.${sig}`

  const tokenRes = await fetch('https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: `grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=${jwt}`,
  })

  const { access_token } = await tokenRes.json()
  return access_token
}

Deno.serve(async (req) => {
  try {
    const { type, recipientIds, senderName, senderId } = await req.json()

    if (!recipientIds?.length) {
      return new Response(JSON.stringify({ sent: 0, reason: 'no recipients' }), { status: 200 })
    }

    const saJson = Deno.env.get('FCM_SERVICE_ACCOUNT')
    if (!saJson) {
      return new Response(JSON.stringify({ error: 'FCM_SERVICE_ACCOUNT secret not set' }), { status: 500 })
    }
    const sa: ServiceAccount = JSON.parse(saJson)

    // Use service role to bypass RLS when reading fcm_token
    const db = createClient(
      Deno.env.get('SUPABASE_URL')!,
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY')!,
    )

    const { data: users, error } = await db
      .from('users')
      .select('fcm_token')
      .in('id', recipientIds)
      .not('fcm_token', 'eq', '')

    if (error || !users?.length) {
      return new Response(JSON.stringify({ sent: 0, reason: 'no tokens found' }), { status: 200 })
    }

    const accessToken = await getAccessToken(sa)

    const results = await Promise.allSettled(
      users.map(async ({ fcm_token }) => {
        const res = await fetch(`https://fcm.googleapis.com/v1/projects/${sa.project_id}/messages:send`, {
          method:  'POST',
          headers: {
            'Authorization': `Bearer ${accessToken}`,
            'Content-Type':  'application/json',
          },
          body: JSON.stringify({
            message: {
              token: fcm_token,
              data: { type, senderId, senderName },
              android: { priority: 'high' },
            },
          }),
        })
        const body = await res.json()
        if (!res.ok) {
          console.error(`FCM error ${res.status}:`, JSON.stringify(body))
          throw new Error(`FCM ${res.status}: ${JSON.stringify(body)}`)
        }
        return body
      }),
    )

    const sent = results.filter(r => r.status === 'fulfilled').length
    const errors = results.filter(r => r.status === 'rejected').map(r => (r as PromiseRejectedResult).reason?.message)
    console.log(`send-notification: type=${type} sent=${sent}/${users.length} errors=${JSON.stringify(errors)}`)
    return new Response(JSON.stringify({ sent, errors }), { status: 200 })

  } catch (err) {
    console.error('send-notification error:', err)
    return new Response(JSON.stringify({ error: String(err) }), { status: 500 })
  }
})
